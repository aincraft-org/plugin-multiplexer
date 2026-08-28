package io.github.aincraft.proxyinspector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

public final class ProxyInspectorPlugin {
    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public ProxyInspectorPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
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

        logger.info("Proxy Inspector enabled: /servers and /plugins");
    }
}
