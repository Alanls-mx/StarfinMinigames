package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.NetworkServerSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only compatibility adapter for the existing sbw_network_* schema. */
final class BedWarsCatalogAdapter implements NetworkCatalogAdapter {
    @Override public String gameId() { return "bedwars"; }

    @Override public NetworkDirectorySnapshot read(Connection connection, BungeeSettings settings, long now)
            throws SQLException {
        List<NetworkServerSnapshot> servers = new ArrayList<NetworkServerSnapshot>();
        List<NetworkArenaSnapshot> arenas = new ArrayList<NetworkArenaSnapshot>();
        for (String id : settings.serverNames.keySet()) {
            NetworkServerSnapshot server = readServer(connection, id, false);
            if (server == null) continue;
            servers.add(server);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT arena_id,mode,arena_group,state,match_id,players,max_players,"
                    + "enabled,editing,joinable,updated_at FROM sbw_network_arenas"
                    + " WHERE server_id=? AND deleted_at=0")) {
                statement.setString(1, id);
                statement.setQueryTimeout(5);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        try { arenas.add(arena(rows, server)); }
                        catch (IllegalArgumentException invalid) { /* malformed catalog row is not routable */ }
                    }
                }
            }
        }
        return new NetworkDirectorySnapshot(servers, arenas, now);
    }

    NetworkServerSnapshot readServer(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT instance_id,online,online_players,max_players,last_heartbeat"
                + " FROM sbw_network_servers WHERE server_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id);
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                try {
                    int capacity = row.getInt("max_players");
                    int players = row.getInt("online_players");
                    if (capacity < 1 || players < 0 || players > capacity) return null;
                    return new NetworkServerSnapshot(id, UUID.fromString(row.getString("instance_id")),
                        row.getBoolean("online"), players, capacity, row.getLong("last_heartbeat"));
                } catch (IllegalArgumentException invalid) { return null; }
            }
        }
    }

    @Override public NetworkArenaSnapshot lockArena(Connection connection, String serverId,
                                                     String arenaId, BungeeSettings settings, long now)
            throws SQLException {
        if (!settings.serverNames.containsKey(serverId)) return null;
        NetworkServerSnapshot server = readServer(connection, serverId, true);
        if (server == null || !server.isAvailable(now, settings.serverTimeoutSeconds * 1000L)) return null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT arena_id,mode,arena_group,state,match_id,players,max_players,"
                + "enabled,editing,joinable,updated_at FROM sbw_network_arenas"
                + " WHERE server_id=? AND arena_id=? AND deleted_at=0 FOR UPDATE")) {
            statement.setString(1, serverId);
            statement.setString(2, arenaId);
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                try { return arena(row, server); }
                catch (IllegalArgumentException invalid) { return null; }
            }
        }
    }

    private NetworkArenaSnapshot arena(ResultSet row, NetworkServerSnapshot server) throws SQLException {
        int capacity = row.getInt("max_players");
        int players = row.getInt("players");
        if (capacity < 1 || players < 0 || players > capacity) throw new IllegalArgumentException("capacity");
        String match = row.getString("match_id");
        return new NetworkArenaSnapshot(gameId(), server.getServerId(), server.getInstanceId(),
            row.getString("arena_id"), row.getString("mode").toLowerCase(java.util.Locale.ROOT),
            row.getString("arena_group"), row.getString("state"),
            match == null ? null : UUID.fromString(match), players, capacity,
            row.getBoolean("enabled"), row.getBoolean("editing"), row.getBoolean("joinable"),
            row.getLong("updated_at"));
    }
}
