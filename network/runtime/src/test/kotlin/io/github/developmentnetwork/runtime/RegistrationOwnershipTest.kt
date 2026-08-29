package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.config.PaperConfigWriter
import io.github.developmentnetwork.runtime.config.SharedForwardingSecret
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import io.github.developmentnetwork.runtime.controller.ReloadNetworkRequest
import io.github.developmentnetwork.runtime.controller.StopNetworkRequest
import io.github.developmentnetwork.runtime.controller.RestartBackendRequest
import io.github.developmentnetwork.runtime.process.ReadinessProbe
import io.github.developmentnetwork.runtime.process.ProcessSupervisor
import io.github.developmentnetwork.runtime.service.NetworkStatusRequest
import io.github.developmentnetwork.runtime.service.RegistrationService
import io.github.developmentnetwork.runtime.service.ReloadService
import io.github.developmentnetwork.runtime.service.RestartService
import io.github.developmentnetwork.runtime.service.StatusService
import io.github.developmentnetwork.runtime.service.StopService
import io.github.developmentnetwork.runtime.service.UnregistrationService
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RegistrationOwnershipTest {
    @Test
    fun externalRegisterUnregisterNeverTouchesPaperAndConcurrentClaimsSerialize() {
        val base = Files.createTempDirectory("runtime-registration")
        val layout = RuntimeLayout(base)
        val serverDir = base.resolve("external")
        writePaperConfig(serverDir, freePort())
        val paperMarker = serverDir.resolve("sentinel.txt").also { Files.writeString(it, "untouched") }
        val port = freePort()
        val server = ServerSocket(port)
        val controllerToken = ControlServer.generateToken()
        val control = ControlServer().serve(layout.proxyControl, controllerToken) { command ->
            when (command) {
                ControlCommand.Reload -> io.github.developmentnetwork.runtime.controller.ControlResponse.success("reloaded")
                ControlCommand.Shutdown -> io.github.developmentnetwork.runtime.controller.ControlResponse.success("stopped")
            }
        }
        AtomicFiles.write(layout.runtimeDir.resolve("lobby.pid"), "12345\n")
        Files.createDirectories(layout.runtimeDir)
        AtomicFiles.write(layout.proxyReady, "ready\n")
        val owner = "external-owner"
        val request = io.github.developmentnetwork.runtime.service.RegisterExternalRequest("external", port, owner, serverDir)
        val service = RegistrationService(layout)
        assertEquals(0, service.execute(request))
        assertEquals("12345\n", Files.readString(layout.runtimeDir.resolve("lobby.pid")))
        assertEquals(0, UnregistrationService(layout).execute(io.github.developmentnetwork.runtime.service.UnregisterExternalRequest("external", owner)))
        assertEquals("untouched", Files.readString(paperMarker))
        assertTrue(Files.exists(serverDir.resolve("server.properties")))
        assertFalse(Files.exists(layout.backend("external").owner))
        val first = thread(start = true) { service.execute(request.copy(name = "same")) }
        val second = thread(start = true) { service.execute(request.copy(name = "same", owner = "different")) }
        first.join(10_000)
        second.join(10_000)
        val results = listOf(first, second)
        assertTrue(results.all { !it.isAlive })
        val registration = io.github.developmentnetwork.runtime.registry.RegistryStore(layout).readRegistration("same")
        assertNotEquals(null, registration)
        server.close()
        control.close()
    }

    @Test
    fun externalRegistrationRejectsActiveCustomProxyPortBeforeMutation() {
        val base = Files.createTempDirectory("runtime-registration-custom-port")
        val layout = RuntimeLayout(base)
        val serverDir = base.resolve("external")
        val proxyPort = freePort()
        val lobbyPort = freePort()
        writePaperConfig(serverDir, proxyPort)
        val paperMarker = serverDir.resolve("sentinel.txt").also { Files.writeString(it, "untouched") }
        io.github.developmentnetwork.runtime.config.VelocityConfigWriter().write(
            layout,
            io.github.developmentnetwork.runtime.config.VelocityConfig(
                proxyPort = proxyPort,
                targetServer = "localhost",
                onlineMode = false,
                lobbyPort = lobbyPort,
            ),
        )
        val previousVelocity = Files.readAllBytes(layout.velocityConfig)
        val token = ControlServer.generateToken()
        val control = ControlServer().serve(layout.proxyControl, token) {
            io.github.developmentnetwork.runtime.controller.ControlResponse.success("reloaded")
        }
        AtomicFiles.write(layout.proxyReady, "ready\n")
        try {
            ServerSocket(proxyPort).use {
                val request = io.github.developmentnetwork.runtime.service.RegisterExternalRequest(
                    name = "external",
                    port = proxyPort,
                    owner = "external-owner",
                    serverDir = serverDir,
                    readinessTimeout = Duration.ofSeconds(1),
                )

                assertEquals(1, RegistrationService(layout).execute(request))
            }
        } finally {
            control.close()
        }

        val registration = io.github.developmentnetwork.runtime.registry.RegistryStore(layout)
            .readRegistration("external")
        assertEquals(null, registration)
        assertFalse(Files.exists(layout.backend("external").owner))
        assertTrue(previousVelocity.contentEquals(Files.readAllBytes(layout.velocityConfig)))
        assertEquals("untouched", Files.readString(paperMarker))
    }

    @Test
    fun reloadIsIdempotentAndStopFallbackDoesNotTouchExternalRegistration() {
        val base = Files.createTempDirectory("runtime-services")
        val layout = RuntimeLayout(base)
        val externalDir = base.resolve("external")
        writePaperConfig(externalDir, freePort())
        val port = freePort()
        ServerSocket(port).use { server ->
            val token = ControlServer.generateToken()
            val control = ControlServer().serve(layout.proxyControl, token) { command ->
                when (command) {
                    ControlCommand.Reload -> io.github.developmentnetwork.runtime.controller.ControlResponse.success()
                    ControlCommand.Shutdown -> io.github.developmentnetwork.runtime.controller.ControlResponse.success()
                }
            }
            AtomicFiles.write(layout.proxyReady, "ready\n")
            assertEquals(0, RegistrationService(layout).execute(io.github.developmentnetwork.runtime.service.RegisterExternalRequest("ext", port, "owner", externalDir)))
            assertEquals(0, ReloadService(layout).execute(ReloadNetworkRequest()))
            assertEquals(0, ReloadService(layout).execute(ReloadNetworkRequest()))
            assertEquals(1, StopService(layout).execute(StopNetworkRequest()))
            assertTrue(Files.exists(externalDir.resolve("server.properties")))
            assertTrue(io.github.developmentnetwork.runtime.registry.RegistryStore(layout).readRegistration("ext") != null)
            control.close()
        }
    }
    @Test
    fun stopFallbackTerminatesOnlyOwnedManagedBackendWithoutControllerLease() {
        val base = Files.createTempDirectory("runtime-stop-managed")
        val layout = RuntimeLayout(base)
        val workDir = Files.createDirectories(base.resolve("managed"))
        val port = ServerSocket(0).use { it.localPort }
        val owned = ProcessSupervisor().launch(
            FakeJavaServer.command(FakeJavaServer.classPath(), port, "managed"),
            workDir,
        )
        try {
            ReadinessProbe().await("127.0.0.1", port, Duration.ofSeconds(5))
            io.github.developmentnetwork.runtime.registry.RegistryStore(layout).register(
                io.github.developmentnetwork.runtime.model.BackendRegistration(
                    io.github.developmentnetwork.runtime.model.BackendName("managed"),
                    port,
                    "managed-owner",
                    io.github.developmentnetwork.runtime.model.OwnershipMode.MANAGED,
                    owned.identity,
                ),
            )

            assertEquals(
                0,
                StopService(layout).execute(
                    StopNetworkRequest(owner = "managed-owner", shutdownTimeout = Duration.ofSeconds(5), managedOnly = true),
                ),
            )
            assertFalse(owned.process.isAlive)
            assertFalse(Files.exists(layout.backend("managed").owner))
        } finally {
            if (owned.process.isAlive) owned.process.destroyForcibly()
        }
    }


    @Test
    fun persistedPortWinsAcrossReloadAndRestartRequest() {
        val base = Files.createTempDirectory("runtime-port-persistence")
        val layout = RuntimeLayout(base)
        val port = freePort()
        val serverDir = base.resolve("managed")
        writePaperConfig(serverDir, port)
        AtomicFiles.write(layout.registryFile, "managed\n")
        AtomicFiles.write(layout.backend("managed").port, "$port\n")
        AtomicFiles.write(layout.backend("managed").owner, "owner\n")
        AtomicFiles.write(layout.backend("managed").mode, "MANAGED\n")
        val request = RestartBackendRequest("managed", "owner", port, serverDir, FakeJavaServer.command(System.getProperty("java.class.path"), port, "restart"))
        assertEquals(1, RestartService(layout).execute(request)) // no live process: fail closed, but persisted port is not rewritten
        assertEquals(port, Files.readString(layout.backend("managed").port).trim().toInt())
    }

    @Test
    fun malformedCommandsReturnStableDiagnosticsAndExitCodes() {
        assertEquals(2, runRuntime(emptyList()))
        assertEquals(2, runRuntime(listOf("network-status", "--base")))
        assertEquals(2, runRuntime(listOf("unknown")))
        assertEquals(2, runRuntime(listOf("register-external", "--base=/tmp", "--name=x", "--port=bad")))
    }

    private fun writePaperConfig(workDir: Path, port: Int) {
        PaperConfigWriter().writeManaged(workDir, io.github.developmentnetwork.runtime.config.PaperConfig(port))
        // external fixtures must already be fully configured; this call only creates the deterministic fixture.
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
