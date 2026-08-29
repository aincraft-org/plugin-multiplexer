package io.github.developmentnetwork

import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** Configuration shared by the network task adapters. */
abstract class DevelopmentNetworkExtension @Inject constructor(objects: ObjectFactory) {
    abstract val networkBase: DirectoryProperty
    abstract val networkBackend: Property<String>
    abstract val networkBackendPort: Property<Int>
    abstract val networkProxyPort: Property<Int>
    abstract val networkJarTask: Property<String>
    abstract val networkDevUsers: Property<String>
    abstract val networkOnlineMode: Property<Boolean>
    abstract val networkRegistrationOwner: Property<String>
    abstract val networkTargetServer: Property<String>
    abstract val networkLobbyPort: Property<Int>
    abstract val networkLobbyMapUrl: Property<String>
    abstract val networkLobbyMapSha256: Property<String>
    abstract val networkLobbyMapRandomUrl: Property<String>
    abstract val networkTimeout: Property<Long>
    abstract val networkShutdownTimeout: Property<Long>
    abstract val networkControlTimeout: Property<Long>
    abstract val networkServerDir: Property<String>

    val targetServer: Property<String> = networkTargetServer
    val devUsers: Property<String> = networkDevUsers
    val onlineMode: Property<Boolean> = networkOnlineMode
}
