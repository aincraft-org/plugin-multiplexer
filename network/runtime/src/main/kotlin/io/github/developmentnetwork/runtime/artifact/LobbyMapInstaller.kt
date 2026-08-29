package io.github.developmentnetwork.runtime.artifact

import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Mutually exclusive lobby map selection modes. */
data class LobbyMapOptions(
    val staticUrl: URI? = null,
    val staticSha256: String? = null,
    val randomUrl: URI? = null,
) {
    constructor(staticUrl: String?, staticSha256: String?, randomUrl: String?) : this(
        staticUrl?.let(URI::create),
        staticSha256,
        randomUrl?.let(URI::create),
    )
}

enum class MapInstallMode { NONE, STATIC, RANDOM }

data class MapInstallResult(
    val installed: Boolean,
    val skipped: Boolean,
    val world: Path,
    val mode: MapInstallMode,
    val archiveSha256: String? = null,
)

/** Validates and atomically installs one immutable lobby world. */
class LobbyMapInstaller(private val fetcher: ArtifactFetcher) {
    /** Test-only seam invoked after reservation verification and before publication. */
    internal var beforePublication: (() -> Unit)? = null
    fun install(workDir: Path, options: LobbyMapOptions): MapInstallResult =
        withInstallationLock(workDir) {
            installLocked(workDir, options)
        }

    private fun installLocked(workDir: Path, options: LobbyMapOptions): MapInstallResult {
        validateOptions(options)
        Files.createDirectories(workDir)
        val selectedMode = mode(options)
        val world = workDir.resolve("world")
        val existing = existingWorldState(world)
        if (existing == ExistingWorld.COMPLETE) {
            return MapInstallResult(installed = false, skipped = true, world = world, mode = selectedMode)
        }

        if (selectedMode == MapInstallMode.NONE) {
            return MapInstallResult(installed = false, skipped = false, world = world, mode = selectedMode)
        }
        if (existing == ExistingWorld.INCOMPLETE) {
            throw IllegalArgumentException("Refusing to replace incomplete world directory; remove $world and retry")
        }
        NoReplaceDirectoryPublisher.requireAvailable(workDir)
        val archive = workDir.resolve(".world-${java.util.UUID.randomUUID()}.zip")
        var checksum: String? = null
        try {
            checksum = when (selectedMode) {
                MapInstallMode.STATIC -> fetcher.fetch(
                    options.staticUrl!!,
                    options.staticSha256!!,
                    archive,
                ).let { options.staticSha256 }
                MapInstallMode.RANDOM -> fetcher.download(options.randomUrl!!, archive)
                MapInstallMode.NONE -> null
            }
            val installed = installArchive(archive, workDir, world)
            return if (installed) {
                MapInstallResult(installed = true, skipped = false, world = world, mode = selectedMode, archiveSha256 = checksum)
            } else {
                // A concurrent creator may have won the destination reservation race.
                MapInstallResult(installed = false, skipped = true, world = world, mode = selectedMode, archiveSha256 = checksum)
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    private fun installArchive(archive: Path, workDir: Path, world: Path): Boolean {
        val extraction = Files.createTempDirectory(workDir, ".world-extract-")
        try {
            val source = validateAndExtract(archive, extraction)
            // The final check prevents needless work when a world is already
            // visible. Publication itself is the kernel no-replace operation:
            // no provider-level move may replace a creator between this check
            // and publication, and the public path never contains extraction
            // files before the operation succeeds.
            if (existingWorldState(world) != ExistingWorld.ABSENT) return false
            beforePublication?.invoke()
            if (existingWorldState(world) != ExistingWorld.ABSENT) return false
            return NoReplaceDirectoryPublisher.publish(source, world)
        } finally {
            deleteTree(extraction)
        }
    }

    /** Validate every central-directory entry before opening any entry stream. */
    private fun validateAndExtract(archive: Path, extraction: Path): Path {
        try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().toList()
                val externalAttributes = readCentralDirectoryAttributes(archive)
                require(entries.size == externalAttributes.size) { "ZIP central directory entry count mismatch" }
                val seen = HashSet<List<String>>()
                val validated = entries.mapIndexed { index, entry ->
                    val parts = safeParts(entry.name)
                    require(seen.add(parts)) { "Unsafe ZIP entry '${entry.name}': duplicate path" }
                    val attributes = externalAttributes[index]
                    val mode = ((attributes ushr 16) and 0xffff).toInt()
                    val dosAttributes = (attributes and 0xffff).toInt()
                    require(dosAttributes and DOS_VOLUME_LABEL == 0) {
                        "Unsafe ZIP entry '${entry.name}': DOS volume labels are not files"
                    }
                    val fileType = mode and UNIX_FILE_TYPE_MASK
                    val dosDirectory = dosAttributes and DOS_DIRECTORY != 0
                    require(!dosDirectory || entry.name.endsWith('/') || fileType == UNIX_DIRECTORY) {
                        "Unsafe ZIP entry '${entry.name}': DOS directory is missing its directory marker"
                    }
                    val directory = entry.name.endsWith('/') || fileType == UNIX_DIRECTORY || dosDirectory
                    when (fileType) {
                        0, UNIX_REGULAR -> Unit
                        UNIX_DIRECTORY -> require(directory) { "Directory entry is missing its directory marker: ${entry.name}" }
                        UNIX_SYMLINK -> throw unsafe(entry.name, "symbolic links are not allowed")
                        else -> throw unsafe(entry.name, "special file type is not allowed")
                    }
                    if (fileType == UNIX_REGULAR && entry.name.endsWith('/')) {
                        throw unsafe(entry.name, "regular entry has a directory marker")
                    }
                    Triple(entry, parts, directory)
                }

                // All names and metadata have passed validation; only now write files.
                validated.forEach { (entry, parts, directory) ->
                    val target = parts.fold(extraction) { current, part -> current.resolve(part) }.normalize()
                    require(target.startsWith(extraction.normalize())) {
                        "ZIP path resolves outside extraction root: ${entry.name}"
                    }
                    if (directory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(target).use { output -> input.transferTo(output) }
                        }
                    }
                }
            }

            val rootLevel = extraction.resolve("level.dat")
            if (isRegularFileNoFollow(rootLevel)) return extraction
            val topEntries = Files.list(extraction).use { stream -> stream.toList() }
            val topDirectories = topEntries.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
            require(topEntries.size == 1 && topDirectories.size == 1) {
                "ZIP must contain level.dat at its root or exactly one top-level world directory"
            }
            val nestedLevel = topDirectories.single().resolve("level.dat")
            require(isRegularFileNoFollow(nestedLevel)) {
                "ZIP must contain level.dat at its root or exactly one top-level world directory"
            }
            return topDirectories.single()
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid map ZIP: ${error.message ?: error::class.simpleName}", error)
        }
    }

    private fun readCentralDirectoryAttributes(archive: Path): List<Long> {
        val bytes = Files.readAllBytes(archive)
        val eocd = findEndOfCentralDirectory(bytes)
            ?: throw ZipException("ZIP end-of-central-directory record is missing")
        val count = littleUnsignedShort(bytes, eocd + 10)
        val offsetLong = littleUnsignedInt(bytes, eocd + 16)
        require(offsetLong <= bytes.size && count < 0xffff) { "ZIP64 archives are not supported" }
        val offset = offsetLong.toInt()
        val result = ArrayList<Long>(count)
        var cursor = offset
        repeat(count) {
            require(cursor + CENTRAL_HEADER_SIZE <= bytes.size && littleUnsignedInt(bytes, cursor) == CENTRAL_SIGNATURE) {
                "Malformed ZIP central directory"
            }
            val nameLength = littleUnsignedShort(bytes, cursor + 28)
            val extraLength = littleUnsignedShort(bytes, cursor + 30)
            val commentLength = littleUnsignedShort(bytes, cursor + 32)
            val end = cursor + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
            require(end <= bytes.size) { "Malformed ZIP central directory entry" }
            result += littleUnsignedInt(bytes, cursor + 38)
            cursor = end
        }
        return result
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        val start = maxOf(0, bytes.size - 65_557)
        for (index in bytes.size - 22 downTo start) {
            if (index >= 0 && index + 22 <= bytes.size && littleUnsignedInt(bytes, index) == EOCD_SIGNATURE) {
                val commentLength = littleUnsignedShort(bytes, index + 20)
                if (index + 22 + commentLength <= bytes.size) return index
            }
        }
        return null
    }

    private fun safeParts(name: String): List<String> {
        require(name.isNotEmpty() && '\u0000' !in name) { "Unsafe ZIP entry '$name': empty or NUL-containing path" }
        require('\\' !in name) { "Unsafe ZIP entry '$name': backslashes are not allowed" }
        require(!name.startsWith('/') && !name.startsWith("//") && !WINDOWS_DRIVE.matches(name)) {
            "Unsafe ZIP entry '$name': absolute path"
        }
        val raw = name.split('/')
        require(raw.none { it == ".." }) { "Unsafe ZIP entry '$name': path traversal" }
        val parts = raw.filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty()) { "Unsafe ZIP entry '$name': empty path" }
        return parts
    }


    private fun existingWorldState(world: Path): ExistingWorld = when {
        !Files.exists(world, NOFOLLOW_LINKS) -> ExistingWorld.ABSENT
        Files.isDirectory(world, NOFOLLOW_LINKS) && isRegularFileNoFollow(world.resolve("level.dat")) -> ExistingWorld.COMPLETE
        else -> ExistingWorld.INCOMPLETE
    }
    private fun validateOptions(options: LobbyMapOptions) {
        val staticAny = options.staticUrl != null || options.staticSha256 != null
        require(options.randomUrl == null || !staticAny) {
            "Set random URL, or both static URL and static SHA-256, or neither"
        }

        require(options.staticUrl == null == (options.staticSha256 == null)) {
            "Static lobby map mode requires both URL and exact SHA-256"
        }
        if (options.staticSha256 != null) {
            require(LOWERCASE_SHA256.matches(options.staticSha256)) {
                "Static lobby map SHA-256 must be exactly 64 lowercase hexadecimal characters"
            }
        }
    }
    private fun <T> withInstallationLock(workDir: Path, action: () -> T): T {
        Files.createDirectories(workDir)
        val lock = workDir.resolve(".world-install.lock")
        FileChannel.open(lock, CREATE, WRITE).use { channel ->
            while (true) {
                try {
                    channel.lock().use { return action() }
                } catch (_: OverlappingFileLockException) {
                    try {
                        Thread.sleep(10)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Interrupted while waiting for lobby world lock $lock", error)
                    }
                }
            }
        }
    }

    private fun mode(options: LobbyMapOptions): MapInstallMode = when {
        options.randomUrl != null -> MapInstallMode.RANDOM
        options.staticUrl != null -> MapInstallMode.STATIC
        else -> MapInstallMode.NONE
    }

    private fun unsafe(name: String, reason: String): IllegalArgumentException =
        IllegalArgumentException("Unsafe ZIP entry '$name': $reason")

    private fun isRegularFileNoFollow(path: Path): Boolean = Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: DirectoryNotEmptyException) {
                    // A concurrent writer can leave a child behind; never delete outside root.
                }
            }
        }
    }

    private enum class ExistingWorld { ABSENT, INCOMPLETE, COMPLETE }

    private companion object {
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val CENTRAL_HEADER_SIZE = 46
        const val UNIX_FILE_TYPE_MASK = 0xf000
        const val UNIX_REGULAR = 0x8000
        const val UNIX_DIRECTORY = 0x4000
        const val UNIX_SYMLINK = 0xa000
        const val DOS_VOLUME_LABEL = 0x08
        const val DOS_DIRECTORY = 0x10
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

        fun littleUnsignedShort(bytes: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset + 2 <= bytes.size) { "Malformed ZIP record" }
            return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
        }

        fun littleUnsignedInt(bytes: ByteArray, offset: Int): Long {
            require(offset >= 0 && offset + 4 <= bytes.size) { "Malformed ZIP record" }
            return (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        }
    }
}
