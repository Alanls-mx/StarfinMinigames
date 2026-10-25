package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import java.sql.Connection;
import java.sql.SQLException;

/** Table-specific adapter. Matchmaking knows only shared snapshots. */
interface NetworkCatalogAdapter {
    String gameId();
    NetworkDirectorySnapshot read(Connection connection, BungeeSettings settings, long now) throws SQLException;
    NetworkArenaSnapshot lockArena(Connection connection, String serverId, String arenaId,
                                   BungeeSettings settings, long now) throws SQLException;
}
