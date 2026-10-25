package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.NetworkServerSnapshot;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

final class NetworkCommand extends Command implements TabExecutor {
    private final StarfinMinigamesBungee plugin;

    NetworkCommand(StarfinMinigamesBungee plugin) {
        super("smnetwork", "starfinminigames.admin");
        this.plugin = plugin;
    }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("starfinminigames.admin")) {
            plugin.send(sender, "&cSem permissao.");
            return;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        if ("reload".equals(sub)) {
            plugin.send(sender, plugin.reloadSafeSettings());
            return;
        }
        if (!Arrays.asList("status", "servers", "games", "arenas", "players").contains(sub)) {
            plugin.send(sender, "&eUso: /smnetwork <status|servers|games|arenas|players|reload>");
            return;
        }
        BungeeSettings settings = plugin.settings();
        if ("status".equals(sub)) {
            plugin.send(sender, "&fRede: " + settings.networkId + " | MySQL: "
                + (plugin.database() == null ? "indisponivel" : "ativo")
                + " | Lobby: " + settings.lobbyServer);
            plugin.send(sender, "&fServidores autorizados: " + settings.serverNames.size()
                + " | Matchmaking: " + (settings.matchmakingEnabled ? "ativo" : "desativado"));
            return;
        }
        if (plugin.database() == null) {
            plugin.send(sender, "&cCatalogo MySQL indisponivel.");
            return;
        }
        plugin.submitIo(() -> {
            try {
                BungeeDatabase database = plugin.database();
                if (database == null) return;
                if ("players".equals(sub)) {
                    plugin.send(sender, "&fJogadores no catalogo ativo: " + database.playerCount());
                    return;
                }
                NetworkDirectorySnapshot directory = database.directory();
                long timeout = settings.serverTimeoutSeconds * 1000L;
                if ("games".equals(sub)) {
                    plugin.send(sender, "&fMinigames com adaptador: bedwars");
                    return;
                }
                if ("servers".equals(sub)) {
                    plugin.send(sender, "&fServidores autorizados: " + settings.serverNames.size());
                    for (String id : settings.serverNames.keySet()) {
                        NetworkServerSnapshot found = null;
                        for (NetworkServerSnapshot server : directory.getServers())
                            if (id.equals(server.getServerId())) { found = server; break; }
                        plugin.send(sender, "&7" + id + " -> " + settings.serverNames.get(id) + " : "
                            + (found == null ? "sem heartbeat" : found.isAvailable(directory.getSampledAtMillis(), timeout)
                                ? "online " + found.getPlayers() + "/" + found.getCapacity()
                                : "indisponivel/expirado"));
                    }
                    return;
                }
                plugin.send(sender, "&fArenas no catalogo: " + directory.getArenas().size());
                for (NetworkArenaSnapshot arena : directory.getArenas())
                    plugin.send(sender, "&7" + arena.getServerId() + "/" + arena.getArenaId()
                        + " [" + arena.getMode() + "] " + arena.getState() + " "
                        + arena.getPlayers() + "/" + arena.getCapacity()
                        + (arena.isAvailable() ? " disponivel" : " fechada"));
            } catch (SQLException error) {
                plugin.getLogger().warning("Diagnostico da rede falhou (" + error.getClass().getSimpleName() + ").");
                plugin.send(sender, "&cFalha ao consultar o catalogo.");
            }
        });
    }

    @Override public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("starfinminigames.admin") || args.length != 1)
            return Collections.emptyList();
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (String sub : Arrays.asList("status", "servers", "games", "arenas", "players", "reload"))
            if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) result.add(sub);
        return result;
    }
}
