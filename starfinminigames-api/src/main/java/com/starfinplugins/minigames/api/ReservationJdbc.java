package com.starfinplugins.minigames.api;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Java 8 JDBC contract shared by proxy and destination; callers own transactions and threads. */
public final class ReservationJdbc {
    public static final String TABLE = "sm_network_reservations";
    private ReservationJdbc() { }

    public static void ensureTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sm_network_reservations ("
                + "reservation_id CHAR(36) NOT NULL PRIMARY KEY,player_uuid CHAR(36) NOT NULL UNIQUE,"
                + "minigame_id VARCHAR(32) NOT NULL,mode VARCHAR(16) NOT NULL,"
                + "server_id VARCHAR(32) NOT NULL,server_instance CHAR(36) NOT NULL,"
                + "arena_id VARCHAR(32) NOT NULL,state VARCHAR(16) NOT NULL,"
                + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,"
                + "group_id CHAR(36) NULL,party_id CHAR(36) NULL,leader_uuid CHAR(36) NULL,"
                + "party_revision BIGINT NOT NULL DEFAULT 0,group_size INT NOT NULL DEFAULT 1,"
                + "team_id VARCHAR(32) NULL,ranked_allocation_id CHAR(36) NULL,"
                + "competitive_context VARCHAR(32) NULL,"
                + "INDEX idx_sm_reservation_arena (server_id,server_instance,arena_id,state,expires_at),"
                + "INDEX idx_sm_reservation_expiry (state,expires_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        // Versioned, additive migration. Existing individual reservations keep NULL group/team.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sm_schema_migrations (version VARCHAR(16) PRIMARY KEY, applied_at BIGINT NOT NULL) ENGINE=InnoDB");
        }
        addColumn(connection, "group_id", "CHAR(36) NULL");
        addColumn(connection, "party_id", "CHAR(36) NULL");
        addColumn(connection, "leader_uuid", "CHAR(36) NULL");
        addColumn(connection, "party_revision", "BIGINT NOT NULL DEFAULT 0");
        addColumn(connection, "group_size", "INT NOT NULL DEFAULT 1");
        addColumn(connection, "team_id", "VARCHAR(32) NULL");
        addColumn(connection, "ranked_allocation_id", "CHAR(36) NULL");
        addColumn(connection, "competitive_context", "VARCHAR(32) NULL");
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT IGNORE INTO sm_schema_migrations (version,applied_at) VALUES ('4.4.1'," + System.currentTimeMillis() + ")");
            statement.execute("INSERT IGNORE INTO sm_schema_migrations (version,applied_at) VALUES ('6.2'," + System.currentTimeMillis() + ")");
        }
    }

    private static void addColumn(Connection connection, String name, String definition) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, TABLE, name)) {
            if (columns.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + definition);
        } catch (SQLException raced) {
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, TABLE, name)) {
                if (!columns.next()) throw raced;
            }
        }
    }

    public static ReservationSnapshot findForPlayer(Connection connection, UUID playerId, boolean lock)
            throws SQLException {
        String query = "SELECT reservation_id,player_uuid,minigame_id,mode,server_id,server_instance,"
            + "arena_id,state,created_at,expires_at,group_id,party_id,leader_uuid,"
            + "party_revision,group_size,team_id,ranked_allocation_id,competitive_context"
            + " FROM " + TABLE + " WHERE player_uuid=?"
            + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, playerId.toString());
            statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? read(row) : null;
            }
        }
    }

    public static void save(Connection connection, ReservationSnapshot reservation, boolean existing, long now)
            throws SQLException {
        String query = existing
            ? "UPDATE " + TABLE + " SET reservation_id=?,minigame_id=?,mode=?,server_id=?,"
                + "server_instance=?,arena_id=?,state='PENDING',created_at=?,expires_at=?,updated_at=?,"
                + "group_id=?,party_id=?,leader_uuid=?,party_revision=?,group_size=?,team_id=?,"
                + "ranked_allocation_id=?,competitive_context=?"
                + " WHERE player_uuid=?"
            : "INSERT INTO " + TABLE + " (reservation_id,minigame_id,mode,server_id,server_instance,"
                + "arena_id,state,created_at,expires_at,updated_at,group_id,party_id,leader_uuid,"
                + "party_revision,group_size,team_id,ranked_allocation_id,competitive_context,player_uuid)"
                + " VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, reservation.getId().toString());
            statement.setString(2, reservation.getMinigameId());
            statement.setString(3, reservation.getMode());
            statement.setString(4, reservation.getServerId());
            statement.setString(5, reservation.getServerInstance().toString());
            statement.setString(6, reservation.getArenaId());
            statement.setLong(7, reservation.getCreatedAtMillis());
            statement.setLong(8, reservation.getExpiresAtMillis());
            statement.setLong(9, now);
            statement.setString(10, uuid(reservation.getGroupId()));
            statement.setString(11, uuid(reservation.getPartyId()));
            statement.setString(12, uuid(reservation.getLeaderId()));
            statement.setLong(13, reservation.getPartyRevision());
            statement.setInt(14, reservation.getGroupSize());
            statement.setString(15, reservation.getTeamId());
            statement.setString(16, uuid(reservation.getRankedAllocationId()));
            statement.setString(17, reservation.getCompetitiveContext());
            statement.setString(18, reservation.getPlayerId().toString());
            statement.executeUpdate();
        }
    }

    public static ReservationSnapshot claim(Connection connection, UUID playerId,
                                            String serverId, UUID instanceId,
                                            long now, long processingTimeoutMillis) throws SQLException {
        ReservationSnapshot row = findForPlayer(connection, playerId, true);
        if (row == null || row.getState() != ReservationState.PENDING || row.getExpiresAtMillis() <= now
                || !row.getServerId().equals(serverId) || !row.getServerInstance().equals(instanceId))
            return null;
        long expires = now + processingTimeoutMillis;
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                + " SET state='CLAIMED',expires_at=?,updated_at=?"
                + " WHERE reservation_id=? AND state='PENDING'")) {
            update.setLong(1, expires);
            update.setLong(2, now);
            update.setString(3, row.getId().toString());
            if (update.executeUpdate() != 1) return null;
        }
        return new ReservationSnapshot(row.getId(), playerId, row.getMinigameId(), row.getMode(),
            serverId, instanceId, row.getArenaId(), ReservationState.CLAIMED,
            row.getCreatedAtMillis(), expires, row.getGroupId(), row.getPartyId(),
            row.getLeaderId(), row.getPartyRevision(), row.getGroupSize(), row.getTeamId(),
            row.getRankedAllocationId(), row.getCompetitiveContext());
    }

    public static boolean finish(Connection connection, UUID id, UUID playerId,
                                 ReservationState terminal, long now) throws SQLException {
        if (terminal != ReservationState.CONFIRMED && terminal != ReservationState.FAILED)
            throw new IllegalArgumentException("terminal state required");
        String accepted = terminal == ReservationState.CONFIRMED ? "'CLAIMED'" : "'PENDING','CLAIMED'";
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + TABLE
                + " SET state=?,updated_at=? WHERE reservation_id=? AND player_uuid=?"
                + " AND state IN (" + accepted + ") AND expires_at>?")) {
            statement.setString(1, terminal.name());
            statement.setLong(2, now);
            statement.setString(3, id.toString());
            statement.setString(4, playerId.toString());
            statement.setLong(5, now);
            return statement.executeUpdate() == 1;
        }
    }

    public static int expire(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + TABLE
                + " SET state='EXPIRED',updated_at=? WHERE state IN ('PENDING','CLAIMED')"
                + " AND expires_at<=?")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            return statement.executeUpdate();
        }
    }

    public static ReservationSnapshot read(ResultSet row) throws SQLException {
        return new ReservationSnapshot(UUID.fromString(row.getString("reservation_id")),
            UUID.fromString(row.getString("player_uuid")), row.getString("minigame_id"),
            row.getString("mode"), row.getString("server_id"),
            UUID.fromString(row.getString("server_instance")), row.getString("arena_id"),
            ReservationState.valueOf(row.getString("state")), row.getLong("created_at"),
            row.getLong("expires_at"), nullableUuid(row.getString("group_id")),
            nullableUuid(row.getString("party_id")), nullableUuid(row.getString("leader_uuid")),
            row.getLong("party_revision"), row.getInt("group_size"), row.getString("team_id"),
            nullableUuid(row.getString("ranked_allocation_id")), row.getString("competitive_context"));
    }
    private static String uuid(UUID id) { return id == null ? null : id.toString(); }
    private static UUID nullableUuid(String value) { return value == null ? null : UUID.fromString(value); }
}
