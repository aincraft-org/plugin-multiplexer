package io.github.developmentnetwork.runtime

import com.sun.net.httpserver.HttpServer
import io.github.developmentnetwork.runtime.artifact.ArtifactFetcher
import io.github.developmentnetwork.runtime.artifact.LobbyMapInstaller
import io.github.developmentnetwork.runtime.artifact.LobbyMapOptions
import io.github.developmentnetwork.runtime.artifact.MapInstallResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
class LobbyMapInstallerTest {
    @Test
    fun rootAndSingleTopLevelFolderWorldsInstallAtomically() {
        val rootWork = Files.createTempDirectory("world-root")
        val rootZip = zip(mapOf("level.dat" to "root", "region/r.0.0.mca" to "r"))
        serve(rootZip).use { fixture ->
            val result = LobbyMapInstaller(ArtifactFetcher()).install(
                rootWork,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )
            assertTrue(result.installed)
            assertEquals("root", Files.readString(rootWork.resolve("world/level.dat")))
        }

        val folderWork = Files.createTempDirectory("world-folder")
        val folderZip = zip(mapOf("MyWorld/level.dat" to "folder", "MyWorld/region/r.0.0.mca" to "r"))
        serve(folderZip).use { fixture ->
            LobbyMapInstaller(ArtifactFetcher()).install(
                folderWork,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )
            assertEquals("folder", Files.readString(folderWork.resolve("world/level.dat")))
        }
    }

    @Test
    fun unsafeZipEntriesAreRejectedBeforeExtraction() {
        val names = listOf(
            "../escape.txt",
            "/absolute.txt",
            "dir\\escape.txt",
            "C:/drive.txt",
            "link",
            "device",
            "fifo",
            "socket",
            "special",
        )
        names.forEachIndexed { index, name ->
            val attrs = when (index) {
                4 -> unixMode(0xA000, 0x1FF)
                5 -> unixMode(0x06000, 0x1FF)
                6 -> unixMode(0x01000, 0x1FF)
                7 -> unixMode(0x0C000, 0x1FF)
                8 -> unixMode(0x02000, 0x1FF)
                else -> 0L
            }
            val work = Files.createTempDirectory("unsafe-map")
            val archive = zipEntries(listOf(EntrySpec(name, "bad", attrs)))
            serve(archive).use { fixture ->
                assertFailsWith<IllegalArgumentException> {
                    LobbyMapInstaller(ArtifactFetcher()).install(
                        work,
                        LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
                    )
                }
            }
            assertFalse(Files.exists(work.resolve("world")))
            assertFalse(Files.exists(work.resolve("escape.txt")))
        }
    }

    @Test
    fun duplicateMalformedAndInvalidWorldArchivesAreRejected() {
        val duplicate = duplicateZip()
        val malformed = byteArrayOf(1, 2, 3, 4)
        val noLevel = zip(mapOf("not-level.txt" to "x"))
        val twoFolders = zip(mapOf("one/level.dat" to "1", "two/level.dat" to "2"))
        listOf(duplicate, malformed, noLevel, twoFolders).forEach { archive ->
            val work = Files.createTempDirectory("invalid-map")
            serve(archive).use { fixture ->
                assertFailsWith<IllegalArgumentException> {
                    LobbyMapInstaller(ArtifactFetcher()).install(
                        work,
                        LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
                    )
                }
            }
            assertFalse(Files.exists(work.resolve("world")))
        }
    }

    @Test
    fun existingWorldIsImmutableAndModesAreExclusive() {
        val work = Files.createTempDirectory("immutable-map")
        Files.createDirectories(work.resolve("world"))
        Files.writeString(work.resolve("world/level.dat"), "original")
        val archive = zip(mapOf("level.dat" to "replacement"))
        serve(archive).use { fixture ->
            val result = LobbyMapInstaller(ArtifactFetcher()).install(
                work,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )
            assertTrue(result.skipped)
            assertEquals("original", Files.readString(work.resolve("world/level.dat")))
        }

        assertFailsWith<IllegalArgumentException> {
            LobbyMapInstaller(ArtifactFetcher()).install(
                Files.createTempDirectory("exclusive-map"),
                LobbyMapOptions(staticUrl = URI("http://static"), staticSha256 = "0".repeat(64), randomUrl = URI("http://random")),
            )
        }
    }
    @Test
    fun dosVolumeLabelsAndInconsistentMetadataAreRejected() {
        listOf(
            EntrySpec("level.dat", "bad", attrs = 0x08L),
            EntrySpec("level.dat", "bad", attrs = unixMode(0x4000, 0x1ff)),
        ).forEach { spec ->
            val work = Files.createTempDirectory("metadata-map")
            serve(zipEntries(listOf(spec))).use { fixture ->
                assertFailsWith<IllegalArgumentException> {
                    LobbyMapInstaller(ArtifactFetcher()).install(
                        work,
                        LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
                    )
                }
            }
            assertFalse(Files.exists(work.resolve("world")))
        }
    }

    @Test
    fun concurrentInstallersSerializeAndOnlyOneDownloadsAndInstalls() {
        val work = Files.createTempDirectory("race-map")
        val archive = zip(mapOf("level.dat" to "race"))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val firstRequest = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/map") { exchange ->
                if (requests.incrementAndGet() == 1) {
                    firstRequest.countDown()
                    releaseFirst.await()
                }
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
            }
            start()
        }
        val url = URI("http://127.0.0.1:${server.address.port}/map")
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<MapInstallResult> {
                    LobbyMapInstaller(ArtifactFetcher()).install(
                        work,
                        LobbyMapOptions(staticUrl = url, staticSha256 = checksum),
                    )
                }
            }
            assertTrue(firstRequest.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals(1, requests.get())
            releaseFirst.countDown()
            val results = futures.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.installed })
            assertEquals(1, results.count { it.skipped })
            assertEquals("race", Files.readString(work.resolve("world/level.dat")))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun worldCreatedWhileDownloadIsInFlightIsNeverReplaced() {
        val work = Files.createTempDirectory("external-race-map")
        val archive = zip(mapOf("level.dat" to "downloaded"))
        val request = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/map") { exchange ->
                request.countDown()
                release.await()
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
            }
            start()
        }
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it) }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<MapInstallResult> {
                LobbyMapInstaller(ArtifactFetcher()).install(
                    work,
                    LobbyMapOptions(
                        staticUrl = URI("http://127.0.0.1:${server.address.port}/map"),
                        staticSha256 = checksum,
                    ),
                )
            }
            assertTrue(request.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Files.createDirectories(work.resolve("world"))
            Files.writeString(work.resolve("world/level.dat"), "external")
            release.countDown()
            val result = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(result.skipped)
            assertEquals("external", Files.readString(work.resolve("world/level.dat")))
        } finally {
            release.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }
    @Test
    fun inaccessibleReservationHidesIncompleteWorldFromReadersAndCreators() {
        val work = Files.createTempDirectory("inaccessible-map")
        val archive = zip(mapOf("level.dat" to "published", "region/r.0.0.mca" to "region"))
        var readerFailure: Throwable? = null
        var creatorFailure: Throwable? = null

        serve(archive).use { fixture ->
            val installer = LobbyMapInstaller(ArtifactFetcher())
            installer.beforePublication = {
                val world = work.resolve("world")
                assertTrue(Files.isDirectory(world, NOFOLLOW_LINKS))
                assertTrue(Files.getPosixFilePermissions(world, NOFOLLOW_LINKS).isEmpty())
                try {
                    Files.list(world).use { it.findAny() }
                } catch (error: Throwable) {
                    readerFailure = error
                }
                try {
                    Files.writeString(world.resolve("creator.txt"), "incomplete")
                } catch (error: Throwable) {
                    creatorFailure = error
                }
            }
            val result = installer.install(
                work,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )

            assertTrue(result.installed)
            assertTrue(readerFailure is IOException)
            assertTrue(creatorFailure is IOException)
            assertEquals("published", Files.readString(work.resolve("world/level.dat")))
        }
        assertNoTemporaryWorldArtifacts(work)
    }

    @Test
    fun populatedCreatorAfterFinalReservationCheckIsNeverReplaced() {
        val work = Files.createTempDirectory("populated-final-window-map")
        val archive = zip(mapOf("level.dat" to "published", "region/r.0.0.mca" to "region"))
        var creatorFailure: Throwable? = null

        serve(archive).use { fixture ->
            val installer = LobbyMapInstaller(ArtifactFetcher())
            installer.beforePublication = {
                val world = work.resolve("world")
                Files.delete(world)
                Files.createDirectory(world)
                try {
                    Files.writeString(world.resolve("creator.txt"), "incomplete")
                } catch (error: Throwable) {
                    creatorFailure = error
                }
            }
            val result = installer.install(
                work,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )

            assertFalse(result.installed)
            assertTrue(result.skipped)
            assertEquals(null, creatorFailure)
            assertEquals("incomplete", Files.readString(work.resolve("world/creator.txt")))
            assertFalse(Files.exists(work.resolve("world/level.dat")))
        }
        assertNoTemporaryWorldArtifacts(work)
    }

    @Test
    fun emptyCreatorAfterFinalReservationCheckIsNeverReplaced() {
        val work = Files.createTempDirectory("empty-final-window-map")
        val archive = zip(mapOf("level.dat" to "published", "region/r.0.0.mca" to "region"))
        serve(archive).use { fixture ->
            val installer = LobbyMapInstaller(ArtifactFetcher())
            installer.beforePublication = {
                val world = work.resolve("world")
                Files.delete(world)
                Files.createDirectory(world)
            }
            val result = installer.install(
                work,
                LobbyMapOptions(staticUrl = fixture.url, staticSha256 = fixture.sha256),
            )

            assertFalse(result.installed)
            assertTrue(result.skipped)
            assertTrue(Files.isDirectory(work.resolve("world"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(work.resolve("world/level.dat")))
        }
        assertNoTemporaryWorldArtifacts(work)
    }

    @Test
    fun unsupportedProviderFailsClosedBeforeDownloading() {
        val archive = Files.createTempFile("unsupported-map", ".zip")
        Files.delete(archive)
        FileSystems.newFileSystem(URI("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fileSystem ->
            val work = fileSystem.getPath("/work")
            assertFailsWith<IOException> {
                LobbyMapInstaller(ArtifactFetcher()).install(
                    work,
                    LobbyMapOptions(randomUrl = URI("http://127.0.0.1:1/map")),
                )
            }
        }
        Files.deleteIfExists(archive)
    }

    private fun assertNoTemporaryWorldArtifacts(work: java.nio.file.Path) {
        assertTrue(Files.list(work).use { stream ->
            stream.noneMatch { path ->
                val name = path.fileName.toString()
                name.endsWith(".zip") ||
                    name.startsWith(".world-extract-") ||
                    name.startsWith(".world-publication-probe-")
            }
        })
    }


    @Test
    fun randomModeDownloadsAndRemovesItsTemporaryArchive() {
        val work = Files.createTempDirectory("random-map")
        val archive = zip(mapOf("level.dat" to "random"))
        serve(archive).use { fixture ->
            val result = LobbyMapInstaller(ArtifactFetcher()).install(work, LobbyMapOptions(randomUrl = fixture.url))
            assertTrue(result.installed)
            assertEquals("random", Files.readString(work.resolve("world/level.dat")))
            assertTrue(Files.list(work).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".zip") } })
        }
    }

    private data class EntrySpec(val name: String, val content: String, val attrs: Long = 0L)
    private data class Fixture(val server: HttpServer, val url: URI, val sha256: String) : AutoCloseable {
        override fun close() = server.stop(0)
    }

    private fun serve(bytes: ByteArray): Fixture {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/map") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Fixture(server, URI("http://127.0.0.1:${server.address.port}/map"), sha)
    }

    private fun zip(entries: Map<String, String>): ByteArray = zipEntries(entries.map { EntrySpec(it.key, it.value) })
    private fun duplicateZip(): ByteArray {
        val original = zip(mapOf("level.dat" to "one"))
        val eocd = original.size - 22
        val centralStart = littleInt(original, eocd + 16).toInt()
        val centralSize = littleInt(original, eocd + 12).toInt()
        val centralRecord = original.copyOfRange(centralStart, centralStart + centralSize)
        val result = ByteArray(original.size + centralSize)
        original.copyInto(result, 0, 0, centralStart + centralSize)
        centralRecord.copyInto(result, centralStart + centralSize)
        original.copyInto(result, centralStart + centralSize * 2, centralStart + centralSize, original.size)
        val newEocd = eocd + centralSize
        writeLittleShort(result, newEocd + 8, 2)
        writeLittleShort(result, newEocd + 10, 2)
        writeLittleInt(result, newEocd + 12, (centralSize * 2).toLong())
        return result
    }

    private fun writeLittleShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun zipEntries(entries: List<EntrySpec>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { spec ->
                val entry = ZipEntry(spec.name)
                zip.putNextEntry(entry)
                zip.write(spec.content.toByteArray())
                zip.closeEntry()
            }
        }
        val bytes = output.toByteArray()
        var central = littleInt(bytes, bytes.size - 22 + 16).toInt()
        entries.forEach { spec ->
            check(littleInt(bytes, central) == 0x02014b50L)
            writeLittleInt(bytes, central + 38, spec.attrs)
            val nameLength = littleShort(bytes, central + 28)
            val extraLength = littleShort(bytes, central + 30)
            val commentLength = littleShort(bytes, central + 32)
            central += 46 + nameLength + extraLength + commentLength
        }
        bytes
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun writeLittleInt(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun unixMode(type: Int, permissions: Int): Long = ((type or permissions).toLong()) shl 16
}
