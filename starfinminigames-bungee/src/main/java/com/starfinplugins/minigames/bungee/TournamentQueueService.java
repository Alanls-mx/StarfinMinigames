package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** Polls persisted games and transfers exactly their confirmed, online roster. */
final class TournamentQueueService {
    private final StarfinMinigamesBungee plugin;
    TournamentQueueService(StarfinMinigamesBungee plugin) { this.plugin = plugin; }
    boolean available() {
        return plugin.settings().networkEnabled && plugin.settings().tournamentsEnabled
            && plugin.database() != null;
    }
    void tick() {
        if (!available()) return;
        plugin.submitIo(() -> {
            try {
                BungeeDatabase db = plugin.database();
                if (db == null) return;
                TournamentQueueStore store = new TournamentQueueStore(db, plugin.settings());
                store.expire();
                NetworkDirectorySnapshot directory = db.directory();
                for (TournamentQueueStore.Request request : store.requested(16)) {
                    if (!plugin.settings().tournamentArenaGroup.equals(request.group)) continue;
                    Map<UUID, UUID> roster = store.roster(request);
                    if (roster.size() != request.teamSize * 2) continue;
                    if (!allOnline(roster.keySet())) continue; // no automatic walkover
                    List<UUID> ids = new ArrayList<UUID>(roster.keySet());
                    if (!plugin.acquireGroup(ids)) continue;
                    boolean allocated = false;
                    try {
                        for (NetworkArenaSnapshot option : NetworkSelector.options(directory,
                                plugin.settings().serverNames, "bedwars", request.mode,
                                false, plugin.settings().serverTimeoutSeconds * 1000L)) {
                            if (!request.group.equals(option.getGroup())
                                    || option.getPlayers() != 0
                                    || !"AVAILABLE".equals(option.getState())
                                    || option.getMatchId() != null) continue;
                            TournamentQueueStore.Allocation allocation =
                                store.allocate(request, option, roster);
                            if (allocation == null) continue;
                            allocated = true;
                            transfer(store, allocation);
                            break;
                        }
                    } finally { if (!allocated) plugin.releaseGroup(ids); }
                }
            } catch (SQLException | RuntimeException error) {
                plugin.getLogger().warning("Ciclo de torneios pendente ("
                    + error.getClass().getSimpleName() + ").");
            }
        });
    }
    private boolean allOnline(java.util.Set<UUID> roster) {
        for (UUID id : roster) if (plugin.proxy().getPlayer(id) == null) return false;
        return true;
    }
    private void transfer(TournamentQueueStore store, TournamentQueueStore.Allocation allocation) {
        List<ReservationSnapshot> rows = allocation.reservations;
        ReservationSnapshot first = rows.get(0);
        String name = plugin.settings().serverNames.get(first.getServerId());
        ServerInfo destination = name == null ? null : plugin.proxy().getServerInfo(name);
        if (destination == null) { review(store, allocation); return; }
        for (ReservationSnapshot row : rows) {
            ProxiedPlayer player = plugin.proxy().getPlayer(row.getPlayerId());
            if (player == null) { review(store, allocation); return; }
        }
        for (ReservationSnapshot row : rows) {
            ProxiedPlayer player = plugin.proxy().getPlayer(row.getPlayerId());
            plugin.trackTransfer(row.getPlayerId(), row);
            plugin.send(player, "&aSeu confronto de torneio esta pronto. Entrando na arena...");
            if (player.getServer() != null && destination.equals(player.getServer().getInfo())) {
                if (!plugin.deliverSameServer(player, destination, row)) {
                    plugin.clearTransfer(row.getPlayerId(), row);
                    review(store, allocation);
                    return;
                }
                plugin.releaseFlight(row.getPlayerId());
                continue;
            }
            player.connect(destination, (connected, error) -> {
                plugin.releaseFlight(row.getPlayerId());
                if (error != null || !Boolean.TRUE.equals(connected)) {
                    plugin.clearTransfer(row.getPlayerId(), row);
                    plugin.submitIo(() -> review(store, allocation));
                    if (plugin.proxy().getPlayer(row.getPlayerId()) == player)
                        plugin.send(player, "&cFalha na transferencia. Confronto enviado para revisao.");
                }
            });
        }
    }
    private void review(TournamentQueueStore store, TournamentQueueStore.Allocation allocation) {
        try {
            store.review(allocation.gameId, "TRANSFER_FAILED");
            for (ReservationSnapshot row : allocation.reservations) {
                plugin.fail(row, false);
                plugin.releaseFlight(row.getPlayerId());
            }
        } catch (SQLException error) {
            plugin.getLogger().warning("Falha ao revisar jogo de torneio " + allocation.gameId);
        }
    }
}
