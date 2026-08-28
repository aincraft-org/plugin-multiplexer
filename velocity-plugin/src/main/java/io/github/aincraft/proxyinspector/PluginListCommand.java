package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class PluginListCommand implements SimpleCommand {
    private final ProxyServer proxy;

    PluginListCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        PluginManager pluginManager = proxy.getPluginManager();
        List<PluginContainer> plugins = pluginManager.getPlugins().stream()
                .sorted(Comparator.comparing(container -> container.getDescription().getId(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (plugins.isEmpty()) {
            invocation.source().sendMessage(Component.text("No Velocity plugins are loaded.", NamedTextColor.YELLOW));
            return;
        }

        invocation.source().sendMessage(
                Component.text("Loaded Velocity plugins (" + plugins.size() + "):", NamedTextColor.AQUA)
        );
        for (PluginContainer plugin : plugins) {
            PluginDescription description = plugin.getDescription();
            String displayName = description.getName().orElse(description.getId());
            String version = description.getVersion().orElse("unknown");
            invocation.source().sendMessage(Component.text(
                    " - " + displayName + " (" + description.getId() + ") v" + version,
                    NamedTextColor.GRAY
            ));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
