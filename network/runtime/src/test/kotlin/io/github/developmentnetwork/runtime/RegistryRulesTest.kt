package io.github.developmentnetwork.runtime

import io.github.developmentnetwork.runtime.model.BackendName
import io.github.developmentnetwork.runtime.model.BackendNames
import io.github.developmentnetwork.runtime.model.BackendRegistration
import io.github.developmentnetwork.runtime.model.OwnershipMode
import io.github.developmentnetwork.runtime.registry.PortAllocator
import io.github.developmentnetwork.runtime.registry.RegistryStore
import io.github.developmentnetwork.runtime.state.AtomicFiles
import io.github.developmentnetwork.runtime.state.RuntimeLayout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryRulesTest {
    @Test
    fun invalidBackendNamesAreRejected() {
        listOf("", "has space", "slash/name", "ümlaut", "a.b", "a/b", "proxy").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { BackendNames.validate(raw) }
            assertFailsWith<IllegalArgumentException> { BackendName(raw) }
        }
        assertEquals("plugin_1-2", BackendNames.validate("plugin_1-2").value)
        assertEquals("proxy2", BackendNames.validate("proxy2").value)
    }

    @Test
    fun proxyNameIsRejectedByRegistryPathsWhileInfrastructureStateRemainsReadable() {
        val base = Files.createTempDirectory("registry-proxy-reserved")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)

        fun assertProxyRejected(action: () -> Unit) {
            val error = assertFailsWith<IllegalArgumentException>(block = action)
            assertTrue(error.message.orEmpty().contains("reserved"))
        }

        assertProxyRejected { layout.backend("proxy") }
        assertProxyRejected { layout.backendPaths("proxy") }
        assertProxyRejected { layout.backendState("proxy") }
        assertProxyRejected { layout.backendPort("proxy") }
        assertProxyRejected { layout.backendOwner("proxy") }
        assertProxyRejected { layout.backendMode("proxy") }
        assertProxyRejected { layout.backendPid("proxy") }
        assertProxyRejected { layout.backendReady("proxy") }
        assertProxyRejected { layout.backendAutoDir("proxy") }
        assertProxyRejected { store.readRegistration("proxy") }
        assertProxyRejected { store.unregister("proxy", "owner") }
        assertProxyRejected { store.writeNames(listOf(BackendName("proxy"))) }
        assertProxyRejected {
            store.register(
                BackendRegistration(BackendName("proxy"), 30123, "owner", OwnershipMode.MANAGED, null),
            )
        }
        assertProxyRejected {
            PortAllocator().allocate("proxy", listOf("proxy"))
        }

        Files.createDirectories(layout.runtimeDir)
        AtomicFiles.write(layout.proxyOwner, "proxy-owner\n")
        AtomicFiles.write(layout.proxyPid, "12345\n")
        assertEquals("proxy-owner\n", Files.readString(layout.proxyOwner))
        assertEquals("12345\n", Files.readString(layout.proxyPid))
        assertTrue(store.readRegistrations().isEmpty())

        AtomicFiles.write(layout.registryFile, "proxy\n")
        assertProxyRejected { store.readNames() }
        assertProxyRejected { store.readRegistrations() }
    }

    @Test
    fun registryNamesAreSortedAndDeduplicatedOnPersistence() {
        val base = Files.createTempDirectory("registry-names")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)

        store.writeNames(listOf(BackendName("zeta"), BackendName("alpha"), BackendName("zeta")))

        assertEquals(listOf(BackendName("alpha"), BackendName("zeta")), store.readNames())
        assertEquals("alpha\nzeta\n", Files.readString(layout.registryFile))
    }

    @Test
    fun persistedPortWinsOverExplicitAndDefault() {
        val allocator = PortAllocator()
        val registry = listOf(BackendName("alpha"), BackendName("bravo"))

        assertEquals(
            30111,
            allocator.allocate(BackendName("bravo"), registry, persisted = 30111, explicit = 30112,
                occupied = emptySet(), reserved = emptySet()),
        )
        assertEquals(
            30112,
            allocator.allocate(BackendName("bravo"), registry, persisted = null, explicit = 30112,
                occupied = emptySet(), reserved = emptySet()),
        )
        assertEquals(
            30067,
            allocator.allocate(BackendName("alpha"), registry, persisted = null, explicit = null,
                occupied = emptySet(), reserved = emptySet()),
        )
    }

    @Test
    fun managedAutomaticAllocationSkipsOccupiedAndReservedPorts() {
        val allocator = PortAllocator()
        val registry = listOf(BackendName("alpha"), BackendName("bravo"))

        assertEquals(
            30069,
            allocator.allocate(BackendName("alpha"), registry, persisted = null, explicit = null,
                occupied = emptySet(), reserved = setOf(30068), occupiedProbe = { it == 30067 }),
        )
    }

    @Test
    fun invalidAndCollidingPortsAreRejectedIncludingProxyAndLobby() {
        val allocator = PortAllocator()
        val name = BackendName("alpha")
        val registry = listOf(name)

        listOf(1, 1023, 65536).forEach { port ->
            assertFailsWith<IllegalArgumentException> {
                allocator.allocate(name, registry, persisted = port, explicit = null,
                    occupied = emptySet(), reserved = emptySet())
            }
        }
        listOf(25565, 30066).forEach { port ->
            assertFailsWith<IllegalArgumentException> {
                allocator.allocate(name, registry, persisted = null, explicit = port,
                    occupied = emptySet(), reserved = emptySet())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            allocator.allocate(name, registry, persisted = null, explicit = 30070,
                occupied = setOf(30070), reserved = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            allocator.allocate(name, registry, persisted = null, explicit = 30071,
                occupied = emptySet(), reserved = setOf(30071))
        }
    }

    @Test
    fun managedProcessIdentityPersistsAndSameOwnerMayChangeMode() {
        val base = Files.createTempDirectory("registry-managed")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)
        val identity = io.github.developmentnetwork.runtime.model.ProcessIdentity(
            pid = 1234,
            startInstant = java.time.Instant.parse("2026-08-28T12:00:00Z"),
            executable = base.resolve("java"),
            workingDirectory = base.resolve("server"),
        )
        val managed = BackendRegistration(
            BackendName("managed"), 30070, "owner", OwnershipMode.MANAGED, identity,
        )

        store.register(managed)
        assertEquals(managed, store.readRegistration("managed"))

        store.register(managed.copy(mode = OwnershipMode.EXTERNAL, process = null))

        assertEquals(OwnershipMode.EXTERNAL, store.readRegistration("managed")?.mode)
        assertNull(store.readRegistration("managed")?.process)
        assertTrue(Files.notExists(layout.backend("managed").pid))
    }

    @Test
    fun registrationsRejectDuplicatePortAndSecondOwnerCannotReplaceName() {
        val base = Files.createTempDirectory("registry-transitions")
        val store = RegistryStore(RuntimeLayout(base))
        val alpha = BackendRegistration(
            BackendName("alpha"), 30067, "owner-a", OwnershipMode.MANAGED,
            process = null,
        )
        store.register(alpha)

        assertFailsWith<IllegalStateException> {
            store.register(alpha.copy(owner = "owner-b"))
        }
        assertFailsWith<IllegalStateException> {
            store.register(
                BackendRegistration(BackendName("bravo"), 30067, "owner-b", OwnershipMode.EXTERNAL, null)
            )
        }
        assertEquals(alpha, store.readRegistration(BackendName("alpha")))
    }

    @Test
    fun anExistingNameWithoutOwnerMetadataCannotBeClaimed() {
        val base = Files.createTempDirectory("registry-orphan")
        val store = RegistryStore(RuntimeLayout(base))
        store.writeNames(listOf(BackendName("orphan")))

        assertFailsWith<IllegalStateException> {
            store.register(
                BackendRegistration(BackendName("orphan"), 30070, "owner", OwnershipMode.MANAGED, null),
            )
        }
    }

    @Test
    fun externalRegistrationsNeverPersistProcessIdentity() {
        val base = Files.createTempDirectory("registry-external")
        val store = RegistryStore(RuntimeLayout(base))
        val registration = BackendRegistration(
            BackendName("external"), 30070, "external-owner", OwnershipMode.EXTERNAL,
            process = null,
        )

        store.register(registration)

        val loaded = store.readRegistration(BackendName("external"))
        assertEquals(OwnershipMode.EXTERNAL, loaded?.mode)
        assertNull(loaded?.process)
        assertTrue(Files.notExists(RuntimeLayout(base).backend(BackendName("external")).pid))
    }

    @Test
    fun hiddenPersistedRegistrationStillReservesItsPort() {
        val base = Files.createTempDirectory("registry-hidden")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)
        val hidden = layout.backend(BackendName("hidden"))
        Files.createDirectories(layout.runtimeDir)
        AtomicFiles.write(hidden.port, "30123\n")
        AtomicFiles.write(hidden.owner, "hidden-owner\n")
        AtomicFiles.write(hidden.mode, "EXTERNAL\n")
        store.writeNames(emptyList())

        assertFailsWith<IllegalStateException> {
            store.register(
                BackendRegistration(BackendName("new-owner"), 30123, "new-owner", OwnershipMode.MANAGED, null),
            )
        }
    }
    @Test
    fun liveProxyStateDoesNotBecomeBackendWhileHiddenClaimRemainsDiscoverable() {
        val base = Files.createTempDirectory("registry-proxy-state")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)
        val hidden = layout.backend(BackendName("hidden"))
        Files.createDirectories(layout.runtimeDir)
        AtomicFiles.write(layout.proxyOwner, "proxy-owner\n")
        AtomicFiles.write(layout.proxyPid, "12345\n")
        AtomicFiles.write(hidden.port, "30123\n")
        AtomicFiles.write(hidden.owner, "hidden-owner\n")
        AtomicFiles.write(hidden.mode, "EXTERNAL\n")
        store.writeNames(emptyList())

        val discovered = store.readRegistrations()
        assertEquals(listOf(BackendName("hidden")), discovered.map { it.name })

        store.register(
            BackendRegistration(BackendName("normal"), 30124, "normal-owner", OwnershipMode.MANAGED, null),
        )
        assertEquals(
            listOf("hidden", "normal"),
            store.readRegistrations().map { it.name.value },
        )
    }


    @Test
    fun externalUnregisterLeavesExternalDirectoryAndManagedOnlyMarkerUntouched() {
        val base = Files.createTempDirectory("registry-external-cleanup")
        val layout = RuntimeLayout(base)
        val store = RegistryStore(layout)
        val name = BackendName("external")
        val registration = BackendRegistration(name, 30070, "external-owner", OwnershipMode.EXTERNAL, null)
        val fixtureDirectory = Files.createTempDirectory("external-server")
        val fixture = fixtureDirectory.resolve("server.properties")
        val original = "online-mode=false\nserver-port=30070\n".toByteArray()
        Files.write(fixture, original)

        store.register(registration)
        val state = layout.backend(name)
        AtomicFiles.write(state.ready, "ready\n")
        AtomicFiles.write(state.autoDir, "${fixtureDirectory}\n")

        assertTrue(store.unregister(name, "external-owner"))

        assertTrue(fixtureDirectory.toFile().exists())
        assertEquals(original.toList(), Files.readAllBytes(fixture).toList())
        assertTrue(Files.exists(state.autoDir))
        assertTrue(Files.notExists(state.port))
        assertTrue(Files.notExists(state.owner))
        assertTrue(Files.notExists(state.mode))
        assertTrue(Files.notExists(state.ready))
        assertTrue(store.readNames().isEmpty())
    }
}
