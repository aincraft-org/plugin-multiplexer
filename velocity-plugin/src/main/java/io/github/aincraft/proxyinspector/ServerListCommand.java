package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class ServerListCommand implements SimpleCommand {
    private static final Comparator<RegisteredServer> BY_NAME = Comparator.comparing(
            server -> server.getServerInfo().getName(),
            String.CASE_INSENSITIVE_ORDER
    );

    private final ProxyServer proxy;

    ServerListCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        List<RegisteredServer> servers = proxy.getAllServers().stream()
                .sorted(BY_NAME)
                .toList();

        if (servers.isEmpty()) {
            invocation.source().sendMessage(Component.text("No servers are registered on this proxy.", NamedTextColor.YELLOW));
            return;
        }

        CommandSource source = invocation.source();
        source.sendMessage(Component.text("Checking registered servers...", NamedTextColor.GRAY));

        List<CompletableFuture<ServerStatus>> statuses = new ArrayList<>(servers.size());
        for (RegisteredServer server : servers) {
            statuses.add(ping(server));
        }

        CompletableFuture<?>[] statusFutures = statuses.toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(statusFutures).thenRun(() -> {
            List<ServerStatus> resolved = statuses.stream()
                    .map(CompletableFuture::join)
                    .toList();
            long onlineCount = resolved.stream().filter(ServerStatus::online).count();
            long offlineCount = resolved.size() - onlineCount;

            source.sendMessage(Component.text(
                    "Servers: " + onlineCount + " online, " + offlineCount + " offline (" + resolved.size() + " total)",
                    NamedTextColor.AQUA
            ));
            if (onlineCount > 0) {
                source.sendMessage(Component.text(
                        "Online: " + resolved.stream()
                                .filter(ServerStatus::online)
                                .map(ServerStatus::name)
                                .collect(Collectors.joining(", ")),
                        NamedTextColor.GREEN
                ));
            }
            if (offlineCount > 0) {
                source.sendMessage(Component.text(
                        "Offline: " + resolved.stream()
                                .filter(status -> !status.online())
                                .map(ServerStatus::name)
                                .collect(Collectors.joining(", ")),
                        NamedTextColor.RED
                ));
            }
            resolved.forEach(status -> source.sendMessage(status.asComponent()));
        });
    }

    private CompletableFuture<ServerStatus> ping(RegisteredServer server) {
        String name = server.getServerInfo().getName();
        InetSocketAddress address = server.getServerInfo().getAddress();
        int playerCount = server.getPlayersConnected().size();

        try {
            return server.ping().handle((ignored, error) -> new ServerStatus(
                    name,
                    address,
                    playerCount,
                    error == null
            ));
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(new ServerStatus(
                    name,
                    address,
                    playerCount,
                    false
            ));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    private record ServerStatus(
            String name,
            InetSocketAddress address,
            int playerCount,
            boolean online
    ) {
        Component asComponent() {
            NamedTextColor color = online ? NamedTextColor.GREEN : NamedTextColor.RED;
            String state = online ? "online" : "offline";
            String endpoint = address.getHostString() + ":" + address.getPort();
            String players = playerCount == 1 ? "1 player" : playerCount + " players";

            return Component.text(" - " + name + " (" + endpoint + ") ", NamedTextColor.GRAY)
                    .append(Component.text(state, color))
                    .append(Component.text(" - " + players, NamedTextColor.GRAY));
        }
    }
}
