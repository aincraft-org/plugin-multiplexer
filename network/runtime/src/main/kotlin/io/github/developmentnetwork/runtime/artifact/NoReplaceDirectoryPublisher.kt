package io.github.developmentnetwork.runtime.artifact

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Optional

/**
 * Publishes a directory with Linux renameat2(RENAME_NOREPLACE).
 *
 * The JDK move API does not promise no-replace semantics for ATOMIC_MOVE. This
 * adapter intentionally supports only the default Linux file system and a JDK
 * exposing the Foreign Function & Memory API. Every other provider/runtime is
 * rejected rather than falling back to a weaker publication operation.
 */
internal object NoReplaceDirectoryPublisher {
    private const val AT_FDCWD = -100
    private const val RENAME_NOREPLACE = 1
    private const val PROBE_PREFIX = ".world-publication-probe-"
    fun requireAvailable(workDir: Path) {
        backendFor(workDir).probe(workDir)
    }

    /**
     * Returns false only when the target already existed at the kernel's
     * no-replace operation. Any other failure is an IOException.
     */
    fun publish(source: Path, target: Path): Boolean {
        requireSupportedPath(source)
        requireSupportedPath(target)
        require(source.fileSystem === target.fileSystem) { "Publication paths must share a file system" }
        val backend = backendFor(source)
        val succeeded = backend.rename(source, target)
        if (succeeded) return true
        if (Files.exists(target, NOFOLLOW_LINKS)) return false
        throw IOException("Linux renameat2(RENAME_NOREPLACE) failed without an existing target: $source -> $target")
    }

    private fun backendFor(path: Path): Backend {
        requireSupportedPath(path)
        return try {
            BackendHolder.backend
        } catch (error: Throwable) {
            throw IOException(
                "Cannot install lobby world safely: Linux JDK Foreign Function & Memory renameat2(RENAME_NOREPLACE) is unavailable; " +
                    "the runtime must expose java.lang.foreign and enable native access (--enable-native-access=ALL-UNNAMED)",
                unwrap(error),
            )
        }
    }

    private fun requireSupportedPath(path: Path) {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("linux") || path.fileSystem !== FileSystems.getDefault()) {
            throw IOException(
                "Cannot install lobby world safely: only the Linux default file-system provider with " +
                    "renameat2(RENAME_NOREPLACE) is supported (provider=${path.fileSystem.provider().scheme})",
            )
        }
    }

    private object BackendHolder {
        val backend: Backend = createBackend()
    }

    private class Backend(
        private val arenaFactory: Method,
        private val arenaClose: Method,
        private val allocateFrom: Method,
        private val invokeWithArguments: Method,
        private val renameHandle: Any,
    ) {
        fun rename(source: Path, target: Path): Boolean {
            val arena = invoke(arenaFactory, null)
            try {
                val oldPath = invoke(allocateFrom, arena, source.toAbsolutePath().normalize().toString())
                val newPath = invoke(allocateFrom, arena, target.toAbsolutePath().normalize().toString())
                val result = invoke(
                    invokeWithArguments,
                    renameHandle,
                    listOf(AT_FDCWD, oldPath, AT_FDCWD, newPath, RENAME_NOREPLACE),
                )
                return (result as Number).toInt() == 0
            } finally {
                invoke(arenaClose, arena)
            }
        }

        /**
         * Exercise both successful publication and refusal to replace an
         * inaccessible existing directory. Cleanup uses only known paths and
         * restores permissions before removal, so a probe failure cannot leave
         * a mode-000 target or replace the primary unsupported-capability error.
         */
        fun probe(workDir: Path) {
            val probe = Files.createTempDirectory(workDir, PROBE_PREFIX)
            val cleanupPaths = ArrayList<Path>(4)
            var primary: Throwable? = null
            try {
                val source = Files.createDirectory(probe.resolve("source"))
                cleanupPaths.add(source)
                val destination = probe.resolve("destination")
                cleanupPaths.add(destination)
                if (!rename(source, destination) || Files.exists(source, NOFOLLOW_LINKS) ||
                    !Files.isDirectory(destination, NOFOLLOW_LINKS)
                ) {
                    throw IOException("Linux renameat2(RENAME_NOREPLACE) did not publish an absent directory")
                }

                val existingSource = Files.createDirectory(probe.resolve("existing-source"))
                cleanupPaths.add(existingSource)
                val existingTarget = Files.createDirectory(probe.resolve("existing-target"), INACCESSIBLE_DIRECTORY)
                cleanupPaths.add(existingTarget)
                if (rename(existingSource, existingTarget) ||
                    !Files.exists(existingSource, NOFOLLOW_LINKS) ||
                    !Files.isDirectory(existingTarget, NOFOLLOW_LINKS)
                ) {
                    throw IOException("Linux renameat2(RENAME_NOREPLACE) did not refuse an existing directory")
                }
            } catch (error: Throwable) {
                primary = error
                throw error
            } finally {
                cleanupProbe(probe, cleanupPaths, primary)
            }
        }
    }

    private fun cleanupProbe(probe: Path, paths: List<Path>, primary: Throwable?) {
        var cleanupFailure: Throwable? = null
        // These are all direct, known probe children. Do not traverse a
        // provider-owned mode-000 directory while trying to clean it up.
        paths.asReversed().forEach { path ->
            try {
                makeRemovable(path)
                Files.deleteIfExists(path)
            } catch (error: Throwable) {
                cleanupFailure = cleanupFailure?.also { it.addSuppressed(error) } ?: error
            }
        }
        try {
            Files.deleteIfExists(probe)
        } catch (error: Throwable) {
            cleanupFailure = cleanupFailure?.also { it.addSuppressed(error) } ?: error
        }
        if (cleanupFailure != null) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }

    private fun makeRemovable(path: Path) {
        // Cleanup is deliberately non-recursive and never follows a
        // replacement symlink. The probe creates only empty directories.
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) return
        Files.setPosixFilePermissions(path, PosixFilePermission.entries.toSet())
    }

    private fun unwrap(error: Throwable): Throwable = when (error) {
        is InvocationTargetException -> unwrap(error.targetException)
        else -> error
    }

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw unwrap(error)
    }

    private fun descriptor(
        functionDescriptorClass: Class<*>,
        memoryLayoutClass: Class<*>,
        returnLayout: Any,
        arguments: List<Any>,
    ): Any {
        val argumentArray = java.lang.reflect.Array.newInstance(memoryLayoutClass, arguments.size)
        arguments.forEachIndexed { index, value ->
            java.lang.reflect.Array.set(argumentArray, index, value)
        }
        val of = functionDescriptorClass.getMethod("of", memoryLayoutClass, argumentArray.javaClass)
        return invoke(of, null, returnLayout, argumentArray)!!
    }

    private fun optionArray(optionClass: Class<*>): Any =
        java.lang.reflect.Array.newInstance(optionClass, 0)

    private fun createBackend(): Backend {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) {
            throw UnsupportedOperationException("renameat2 is Linux-specific")
        }
        val arenaClass = Class.forName("java.lang.foreign.Arena")
        val linkerClass = Class.forName("java.lang.foreign.Linker")
        val symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup")
        val memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment")
        val memoryLayoutClass = Class.forName("java.lang.foreign.MemoryLayout")
        val functionDescriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor")
        val valueLayoutClass = Class.forName("java.lang.foreign.ValueLayout")
        val optionClass = Class.forName("java.lang.foreign.Linker\$Option")

        val linker = invoke(linkerClass.getMethod("nativeLinker"), null)!!
        val lookup = invoke(linkerClass.getMethod("defaultLookup"), linker)!!
        val symbol = invoke(
            symbolLookupClass.getMethod("find", String::class.java),
            lookup,
            "renameat2",
        ) as Optional<*>
        val renameAddress = symbol.orElse(null)
            ?: throw UnsupportedOperationException("libc does not expose renameat2")

        val javaInt = valueLayoutClass.getField("JAVA_INT").get(null)
        val address = valueLayoutClass.getField("ADDRESS").get(null)
        val descriptor = descriptor(
            functionDescriptorClass,
            memoryLayoutClass,
            javaInt,
            listOf(javaInt, address, javaInt, address, javaInt),
        )
        val optionArray = optionArray(optionClass)
        val downcall = linkerClass.getMethod(
            "downcallHandle",
            memorySegmentClass,
            functionDescriptorClass,
            optionArray.javaClass,
        )
        val renameHandle = invoke(downcall, linker, renameAddress, descriptor, optionArray)!!

        return Backend(
            arenaFactory = arenaClass.getMethod("ofConfined"),
            arenaClose = arenaClass.getMethod("close"),
            allocateFrom = try {
                arenaClass.getMethod("allocateFrom", String::class.java)
            } catch (_: NoSuchMethodException) {
                arenaClass.getMethod("allocateUtf8String", String::class.java)
            },
            invokeWithArguments = Class.forName("java.lang.invoke.MethodHandle")
                .getMethod("invokeWithArguments", List::class.java),
            renameHandle = renameHandle,
        )
    }

    private val INACCESSIBLE_DIRECTORY = PosixFilePermissions.asFileAttribute(emptySet<PosixFilePermission>())
}
