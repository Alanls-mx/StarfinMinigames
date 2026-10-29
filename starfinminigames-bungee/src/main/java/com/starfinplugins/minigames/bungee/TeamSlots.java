package com.starfinplugins.minigames.bungee;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.StringReader;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

/** Generic capacity accounting for the team buckets advertised by a game adapter. */
final class TeamSlots {
    private final Map<String,Integer> capacities = new LinkedHashMap<String,Integer>();
    private final Map<String,Integer> occupied = new LinkedHashMap<String,Integer>();

    TeamSlots(Connection connection, String server, String instance, String arena, long now)
            throws SQLException {
        String yaml;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT configuration_yaml FROM sbw_network_arenas WHERE server_id=? AND arena_id=?")) {
            statement.setString(1, server); statement.setString(2, arena);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Arena sem configuracao publicada");
                yaml = row.getString(1);
            }
        }
        try {
            Configuration root = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(new StringReader(yaml));
            Configuration teams = root.getSection("teams");
            if (teams != null) for (String id : teams.getKeys()) {
                int capacity = teams.getInt(id + ".capacity", 0);
                if (capacity > 0 && capacity <= 100) {
                    capacities.put(id, capacity);
                    occupied.put(id, 0);
                }
            }
        } catch (RuntimeException malformed) {
            throw new SQLException("Configuracao de equipes invalida", malformed);
        }
        if (capacities.size() < 2) throw new SQLException("Arena sem equipes publicadas");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT team_id,COUNT(*) FROM sbw_network_players WHERE server_id=?"
                + " AND server_instance=? AND arena_id=? AND online=1 AND team_id IS NOT NULL"
                + " GROUP BY team_id")) {
            statement.setString(1, server); statement.setString(2, instance);
            statement.setString(3, arena);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String team = rows.getString(1);
                    if (occupied.containsKey(team)) occupied.put(team, rows.getInt(2));
                }
            }
        }
        // Confirmed transfers count until the next roster heartbeat. The left join avoids
        // double counting after the destination publishes the admitted player.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.team_id,COUNT(*) FROM sm_network_reservations r"
                + " LEFT JOIN sbw_network_players p ON p.player_uuid=r.player_uuid"
                + " AND p.server_id=r.server_id AND p.server_instance=r.server_instance"
                + " WHERE r.server_id=? AND r.server_instance=?"
                + " AND r.arena_id=? AND r.team_id IS NOT NULL"
                + " AND r.state IN ('PENDING','CLAIMED','CONFIRMED') AND r.expires_at>?"
                + " AND (p.player_uuid IS NULL OR p.online<>1 OR p.arena_id IS NULL"
                + " OR p.arena_id<>r.arena_id OR p.team_id IS NULL OR p.team_id<>r.team_id)"
                + " AND (r.state<>'CONFIRMED' OR p.player_uuid IS NULL"
                + " OR (p.online=1 AND (p.arena_id IS NULL OR p.arena_id<>r.arena_id"
                + " OR p.team_id IS NULL)))"
                + " GROUP BY r.team_id")) {
            statement.setString(1, server); statement.setString(2, instance);
            statement.setString(3, arena); statement.setLong(4, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String team = rows.getString(1);
                    if (occupied.containsKey(team)) occupied.put(team, occupied.get(team) + rows.getInt(2));
                }
            }
        }
    }

    int maximum() {
        int max = 0;
        for (int capacity : capacities.values()) max = Math.max(max, capacity);
        return max;
    }

    Map<String, Integer> emptyCapacities() {
        for (int used : occupied.values()) if (used != 0) return java.util.Collections.emptyMap();
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(capacities));
    }

    String assign(int members) {
        String selected = null;
        for (Map.Entry<String,Integer> team : capacities.entrySet()) {
            int used = occupied.get(team.getKey());
            if (team.getValue() - used < members) continue;
            if (selected == null || used < occupied.get(selected)) selected = team.getKey();
        }
        if (selected != null) occupied.put(selected, occupied.get(selected) + members);
        return selected;
    }
}
