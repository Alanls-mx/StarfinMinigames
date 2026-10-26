package com.starfinplugins.minigames.bungee;

import com.mysql.cj.jdbc.Driver;
import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.NetworkServerSnapshot;
import com.starfinplugins.minigames.api.ReservationJdbc;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.minigames.api.ReservationState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

/** All methods block and must be called only by the proxy's bounded I/O executor. */
final class BungeeDatabase implements AutoCloseable {
    private volatile BungeeSettings settings;
    private final NetworkCatalogAdapter bedwars = new BedWarsCatalogAdapter();
    private final HikariDataSource pool;

    BungeeDatabase(BungeeSettings settings) throws SQLException {
        this.settings = settings;
        HikariConfig config = new HikariConfig();
        config.setPoolName("StarfinMinigames-Bungee");
        config.setDriverClassName(Driver.class.getName());
        config.setJdbcUrl("jdbc:mysql://" + settings.mysqlHost + ":" + settings.mysqlPort
            + "/" + settings.mysqlDatabase + "?sslMode=" + settings.sslMode
            + "&connectionTimeZone=UTC&characterEncoding=UTF-8");
        config.setUsername(settings.mysqlUser);
        config.setPassword(settings.mysqlPassword);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setInitializationFailTimeout(5000);
        HikariDataSource created = new HikariDataSource(config);
        try (Connection connection = created.getConnection()) {
            ReservationJdbc.ensureTable(connection);
            // Fail fast on an incompatible or missing BedWars catalog.
            bedwars.read(connection, settings, System.currentTimeMillis());
        } catch (SQLException | RuntimeException error) {
            created.close();
            throw error;
        }
        pool = created;
    }

    NetworkDirectorySnapshot directory() throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return bedwars.read(connection, settings, System.currentTimeMillis());
        }
    }

    ReservationSnapshot reserve(UUID playerId, String gameId, String mode) throws SQLException {
        if (!bedwars.gameId().equalsIgnoreCase(gameId)) return null;
        NetworkDirectorySnapshot directory = directory();
        List<NetworkArenaSnapshot> options = NetworkSelector.options(directory, settings.serverNames,
            gameId, mode, settings.preferExisting, settings.serverTimeoutSeconds * 1000L);
        for (NetworkArenaSnapshot option : options) {
            ReservationSnapshot row = tryReserve(playerId, option);
            if (row != null) return row;
        }
        return null;
    }

    /** Reserve one arena for the whole Party, or none. The arena lock serializes capacity checks. */
    List<ReservationSnapshot> reserveGroup(List<UUID> playerIds, String gameId, String mode)
            throws SQLException {
        if (playerIds == null || playerIds.isEmpty() || playerIds.size() > 64
                || playerIds.contains(null) || new HashSet<UUID>(playerIds).size() != playerIds.size())
            throw new IllegalArgumentException("Grupo invalido");
        if (!bedwars.gameId().equalsIgnoreCase(gameId)) return Collections.emptyList();
        List<UUID> ordered = new ArrayList<UUID>(playerIds);
        Collections.sort(ordered);
        NetworkDirectorySnapshot directory = directory();
        List<NetworkArenaSnapshot> options = NetworkSelector.options(directory, settings.serverNames,
            gameId, mode, settings.preferExisting, settings.serverTimeoutSeconds * 1000L);
        for (NetworkArenaSnapshot option : options) {
            List<ReservationSnapshot> rows = tryReserveGroup(ordered, option);
            if (!rows.isEmpty()) return rows;
        }
        return Collections.emptyList();
    }

    private List<ReservationSnapshot> tryReserveGroup(List<UUID> players,
                                                       NetworkArenaSnapshot option) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();
                NetworkArenaSnapshot arena = bedwars.lockArena(connection, option.getServerId(),
                    option.getArenaId(), settings, now);
                if (arena == null || !arena.isAvailable()
                        || !arena.getInstanceId().equals(option.getInstanceId())
                        || !arena.getMode().equalsIgnoreCase(option.getMode())
                        || arena.getUpdatedAtMillis() > now
                        || now - arena.getUpdatedAtMillis() > settings.serverTimeoutSeconds * 1000L) {
                    connection.rollback(); return Collections.emptyList();
                }
                NetworkServerSnapshot server = ((BedWarsCatalogAdapter) bedwars)
                    .readServer(connection, arena.getServerId(), false);
                if (server == null || !server.getInstanceId().equals(arena.getInstanceId())
                        || !server.isAvailable(now, settings.serverTimeoutSeconds * 1000L)
                        || server.getPlayers() + countActive(connection, arena.getServerId(), null,
                            arena.getInstanceId(), now) + players.size() > server.getCapacity()
                        || arena.getPlayers() + countActive(connection, arena.getServerId(),
                            arena.getArenaId(), arena.getInstanceId(), now) + players.size()
                            > arena.getCapacity()) {
                    connection.rollback(); return Collections.emptyList();
                }
                List<ReservationSnapshot> rows = new ArrayList<ReservationSnapshot>();
                List<Boolean> existing = new ArrayList<Boolean>();
                for (UUID player : players) {
                    ReservationSnapshot previous = ReservationJdbc.findForPlayer(connection,
                        player, true);
                    if (previous != null && (previous.getState() == ReservationState.PENDING
                            || previous.getState() == ReservationState.CLAIMED)
                            && previous.getExpiresAtMillis() > now)
                        throw new IllegalStateException("Transferencia de membro pendente");
                    existing.add(previous != null);
                    rows.add(new ReservationSnapshot(UUID.randomUUID(), player,
                        arena.getMinigameId(), arena.getMode(), arena.getServerId(),
                        arena.getInstanceId(), arena.getArenaId(), ReservationState.PENDING,
                        now, now + settings.reservationTimeoutSeconds * 1000L));
                }
                for (int i = 0; i < rows.size(); i++)
                    ReservationJdbc.save(connection, rows.get(i), existing.get(i), now);
                connection.commit();
                return rows;
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            } finally { connection.setAutoCommit(true); }
        }
    }

    private ReservationSnapshot tryReserve(UUID playerId, NetworkArenaSnapshot option) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();
                NetworkArenaSnapshot arena = bedwars.lockArena(connection, option.getServerId(),
                    option.getArenaId(), settings, now);
                if (arena == null || !arena.isAvailable()
                        || !arena.getInstanceId().equals(option.getInstanceId())
                        || !arena.getMode().equalsIgnoreCase(option.getMode())
                        || arena.getUpdatedAtMillis() > now
                        || now - arena.getUpdatedAtMillis() > settings.serverTimeoutSeconds * 1000L) {
                    connection.rollback();
                    return null;
                }
                NetworkServerSnapshot server = ((BedWarsCatalogAdapter) bedwars)
                    .readServer(connection, arena.getServerId(), false);
                if (server == null || !server.getInstanceId().equals(arena.getInstanceId())
                        || !server.isAvailable(now, settings.serverTimeoutSeconds * 1000L)) {
                    connection.rollback();
                    return null;
                }
                ReservationSnapshot previous = ReservationJdbc.findForPlayer(connection, playerId, true);
                if (previous != null && (previous.getState() == ReservationState.PENDING
                        || previous.getState() == ReservationState.CLAIMED)
                        && previous.getExpiresAtMillis() > now)
                    throw new IllegalStateException("Ja existe uma transferencia em andamento.");
                if (server.getPlayers() + countActive(connection, arena.getServerId(), null,
                        arena.getInstanceId(), now) >= server.getCapacity()
                        || arena.getPlayers() + countActive(connection, arena.getServerId(),
                            arena.getArenaId(), arena.getInstanceId(), now) >= arena.getCapacity()) {
                    connection.rollback();
                    return null;
                }
                ReservationSnapshot row = new ReservationSnapshot(UUID.randomUUID(), playerId,
                    arena.getMinigameId(), arena.getMode(), arena.getServerId(),
                    arena.getInstanceId(), arena.getArenaId(), ReservationState.PENDING,
                    now, now + settings.reservationTimeoutSeconds * 1000L);
                ReservationJdbc.save(connection, row, previous != null, now);
                connection.commit();
                return row;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(true); }
        }
    }

    private int countActive(Connection connection, String serverId, String arenaId,
                            UUID instanceId, long now) throws SQLException {
        String query = "SELECT COUNT(*) FROM " + ReservationJdbc.TABLE
            + " WHERE server_id=? AND server_instance=? AND state IN ('PENDING','CLAIMED')"
            + " AND expires_at>?" + (arenaId == null ? "" : " AND arena_id=?");
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, serverId);
            statement.setString(2, instanceId.toString());
            statement.setLong(3, now);
            if (arenaId != null) statement.setString(4, arenaId);
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    void fail(ReservationSnapshot reservation) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            ReservationJdbc.finish(connection, reservation.getId(), reservation.getPlayerId(),
                ReservationState.FAILED, System.currentTimeMillis());
        }
    }

    void failPendingOnly(ReservationSnapshot reservation) throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE " + ReservationJdbc.TABLE
                 + " SET state='FAILED',updated_at=? WHERE reservation_id=? AND player_uuid=?"
                 + " AND state='PENDING'")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, reservation.getId().toString());
            statement.setString(3, reservation.getPlayerId().toString());
            statement.executeUpdate();
        }
    }

    void updateSettings(BungeeSettings next) { settings = next; }

    int expire() throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return ReservationJdbc.expire(connection, System.currentTimeMillis());
        }
    }

    int playerCount() throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM sbw_network_players WHERE online=1 AND last_seen>?")) {
            statement.setLong(1, System.currentTimeMillis() - settings.serverTimeoutSeconds * 1000L);
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    @Override public void close() { pool.close(); }
}
