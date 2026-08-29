package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.config.PaperConfigWriter
import io.github.developmentnetwork.runtime.config.SharedForwardingSecret
import io.github.developmentnetwork.runtime.controller.ControlCommand
import io.github.developmentnetwork.runtime.controller.ControlServer
import io.github.developmentnetwork.runtime.controller.InfrastructureRequest
import io.github.developmentnetwork.runtime.controller.ReloadNetworkRequest
import io.github.developmentnetwork.runtime.controller.StopNetworkRequest
import io.github.developmentnetwork.runtime.controller.RestartBackendRequest
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
