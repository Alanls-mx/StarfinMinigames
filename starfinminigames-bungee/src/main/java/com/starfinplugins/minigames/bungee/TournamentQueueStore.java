package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.ReservationJdbc;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.minigames.api.ReservationState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Atomic full-roster allocation against the published BedWars arena catalog. */
final class TournamentQueueStore {
    static final class Request {
        final UUID gameId, matchId, teamA, teamB;
        final String tournamentId, mode, group;
        final int teamSize;
        Request(UUID gameId, UUID matchId, UUID teamA, UUID teamB,
                String tournamentId, String mode, String group, int teamSize) {
            this.gameId = gameId; this.matchId = matchId; this.teamA = teamA;
            this.teamB = teamB; this.tournamentId = tournamentId;
            this.mode = mode; this.group = group; this.teamSize = teamSize;
        }
    }
    static final class Allocation {
        final UUID gameId, allocationId;
        final List<ReservationSnapshot> reservations;
        Allocation(UUID gameId, UUID allocationId, List<ReservationSnapshot> reservations) {
            this.gameId = gameId; this.allocationId = allocationId;
            this.reservations = Collections.unmodifiableList(reservations);
        }
    }
    private final BungeeDatabase db;
    private final BungeeSettings settings;
    TournamentQueueStore(BungeeDatabase db, BungeeSettings settings) {
        this.db = db; this.settings = settings;
    }
    static void ensureSchema(Connection c) throws SQLException {
        try (Statement sql = c.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS sm_tournament_arena_leases ("
                + "server_id VARCHAR(32) NOT NULL,arena_id VARCHAR(32) NOT NULL,"
                + "game_id CHAR(36) NOT NULL,expires_at BIGINT NOT NULL,"
                + "PRIMARY KEY(server_id,arena_id),UNIQUE KEY uq_sm_tournament_lease_game(game_id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }
    List<Request> requested(int limit) throws SQLException {
        List<Request> result = new ArrayList<Request>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT g.game_id,g.match_id,m.team_a,m.team_b,g.tournament_id,"
                + "t.mode,t.arena_group,t.team_size FROM sbw_tournament_games g"
                + " JOIN sbw_tournaments t ON t.tournament_id=g.tournament_id"
                + " JOIN sbw_tournament_matches m ON m.match_id=g.match_id"
                + " WHERE g.state='REQUESTED' AND t.state='RUNNING'"
                + " AND m.state='IN_PROGRESS' ORDER BY g.created_at LIMIT ?")) {
            query.setInt(1, Math.max(1, Math.min(32, limit)));
            try (ResultSet row = query.executeQuery()) { while (row.next()) {
                result.add(new Request(UUID.fromString(row.getString(1)),
                    UUID.fromString(row.getString(2)), UUID.fromString(row.getString(3)),
                    UUID.fromString(row.getString(4)), row.getString(5), row.getString(6),
                    row.getString(7), row.getInt(8)));
            } }
        }
        return result;
    }
    Map<UUID, UUID> roster(Request request) throws SQLException {
        Map<UUID, UUID> roster = new LinkedHashMap<UUID, UUID>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT player_uuid,team_id FROM sbw_tournament_members"
                + " WHERE tournament_id=? AND team_id IN (?,?) AND confirmed=1"
                + " ORDER BY player_uuid")) {
            query.setString(1, request.tournamentId);
            query.setString(2, request.teamA.toString());
            query.setString(3, request.teamB.toString());
            try (ResultSet row = query.executeQuery()) { while (row.next())
                roster.put(UUID.fromString(row.getString(1)), UUID.fromString(row.getString(2)));
            }
        }
        return roster;
    }
    Allocation allocate(Request request, NetworkArenaSnapshot option,
            Map<UUID, UUID> expectedRoster) throws SQLException {
        if (expectedRoster.size() != request.teamSize * 2) return null;
        long now = System.currentTimeMillis();
        try (Connection c = db.connection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                // Lock tournament then game, matching backend administration order.
                try (PreparedStatement tournament = c.prepareStatement(
                        "SELECT state,mode,arena_group,team_size FROM sbw_tournaments"
                        + " WHERE tournament_id=? FOR UPDATE")) {
                    tournament.setString(1, request.tournamentId);
                    try (ResultSet row = tournament.executeQuery()) {
                        if (!row.next() || !"RUNNING".equals(row.getString(1))
                                || !request.mode.equals(row.getString(2))
                                || !request.group.equals(row.getString(3))
                                || request.teamSize != row.getInt(4)) {
                            c.rollback(); return null;
                        }
                    }
                }
                try (PreparedStatement game = c.prepareStatement(
                        "SELECT state FROM sbw_tournament_games WHERE game_id=? FOR UPDATE")) {
                    game.setString(1, request.gameId.toString());
                    try (ResultSet row = game.executeQuery()) {
                        if (!row.next() || !"REQUESTED".equals(row.getString(1))) {
                            c.rollback(); return null;
                        }
                    }
                }
                Map<UUID, UUID> currentRoster = new LinkedHashMap<UUID, UUID>();
                try (PreparedStatement members = c.prepareStatement(
                        "SELECT player_uuid,team_id FROM sbw_tournament_members"
                        + " WHERE tournament_id=? AND team_id IN (?,?) AND confirmed=1"
                        + " ORDER BY player_uuid FOR UPDATE")) {
                    members.setString(1, request.tournamentId);
                    members.setString(2, request.teamA.toString());
                    members.setString(3, request.teamB.toString());
                    try (ResultSet row = members.executeQuery()) { while (row.next())
                        currentRoster.put(UUID.fromString(row.getString(1)),
                            UUID.fromString(row.getString(2)));
                    }
                }
                if (!currentRoster.equals(expectedRoster)) { c.rollback(); return null; }
                if (!checkedIn(c, request, currentRoster.keySet())) {
                    c.rollback(); return null;
                }
                try (PreparedStatement lease = c.prepareStatement(
                        "SELECT expires_at FROM sm_tournament_arena_leases"
                        + " WHERE server_id=? AND arena_id=? FOR UPDATE")) {
                    lease.setString(1, option.getServerId()); lease.setString(2, option.getArenaId());
                    try (ResultSet row = lease.executeQuery()) {
                        if (row.next() && row.getLong(1) > now) { c.rollback(); return null; }
                    }
                }
                NetworkArenaSnapshot arena = new BedWarsCatalogAdapter().lockArena(c,
                    option.getServerId(), option.getArenaId(), settings, now);
                if (arena == null || !arena.isAvailable() || arena.getPlayers() != 0
                        || !"AVAILABLE".equals(arena.getState()) || arena.getMatchId() != null
                        || !request.group.equals(arena.getGroup())
                        || !request.mode.equals(arena.getMode())
                        || !option.getInstanceId().equals(arena.getInstanceId())) {
                    c.rollback(); return null;
                }
                Map<String, Integer> capacities = new TeamSlots(c, arena.getServerId(),
                    arena.getInstanceId().toString(), arena.getArenaId(), now).emptyCapacities();
                String first = null, second = null;
                for (Map.Entry<String, Integer> entry : capacities.entrySet()) {
                    if (entry.getValue() < request.teamSize) continue;
                    if (first == null) first = entry.getKey();
                    else { second = entry.getKey(); break; }
                }
                if (second == null || arena.getCapacity() < request.teamSize * 2) {
                    c.rollback(); return null;
                }
                for (UUID player : currentRoster.keySet()) {
                    ReservationSnapshot previous = ReservationJdbc.findForPlayer(c, player, true);
                    if (previous != null && previous.getExpiresAtMillis() > now
                            && (previous.getState() == ReservationState.PENDING
                                || previous.getState() == ReservationState.CLAIMED)) {
                        c.rollback(); return null;
                    }
                }
                UUID allocation = UUID.randomUUID();
                long deadline = now + settings.tournamentAllocationSeconds * 1000L;
                List<ReservationSnapshot> reservations = new ArrayList<ReservationSnapshot>();
                for (Map.Entry<UUID, UUID> entry : currentRoster.entrySet()) {
                    UUID player = entry.getKey(); UUID team = entry.getValue();
                    String arenaTeam = team.equals(request.teamA) ? first : second;
                    ReservationSnapshot previous = ReservationJdbc.findForPlayer(c, player, true);
                    ReservationSnapshot reservation = new ReservationSnapshot(UUID.randomUUID(), player,
                        "bedwars", request.mode, arena.getServerId(), arena.getInstanceId(),
                        arena.getArenaId(), ReservationState.PENDING, now, deadline,
                        team, null, null, 0L, request.teamSize, arenaTeam,
                        null, null, request.gameId, allocation);
                    ReservationJdbc.save(c, reservation, previous != null, now);
                    reservations.add(reservation);
                }
                try (PreparedStatement game = c.prepareStatement(
                        "UPDATE sbw_tournament_games SET state='ALLOCATED',allocation_id=?,"
                        + "server_id=?,server_instance=?,arena_id=?,arena_team_a=?,arena_team_b=?,"
                        + "lease_expires=?,updated_at=? WHERE game_id=? AND state='REQUESTED'")) {
                    game.setString(1, allocation.toString());
                    game.setString(2, arena.getServerId());
                    game.setString(3, arena.getInstanceId().toString());
                    game.setString(4, arena.getArenaId()); game.setString(5, first);
                    game.setString(6, second); game.setLong(7, deadline);
                    game.setLong(8, now); game.setString(9, request.gameId.toString());
                    if (game.executeUpdate() != 1) throw new SQLException("Jogo mudou de estado");
                }
                try (PreparedStatement lease = c.prepareStatement(
                        "INSERT INTO sm_tournament_arena_leases VALUES(?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE game_id=VALUES(game_id),"
                        + "expires_at=VALUES(expires_at)")) {
                    lease.setString(1, arena.getServerId()); lease.setString(2, arena.getArenaId());
                    lease.setString(3, request.gameId.toString()); lease.setLong(4, deadline);
                    lease.executeUpdate();
                }
                c.commit(); return new Allocation(request.gameId, allocation, reservations);
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    private static boolean checkedIn(Connection c, Request request, Set<UUID> roster)
            throws SQLException {
        boolean required;
        try (PreparedStatement policy = c.prepareStatement(
                "SELECT required FROM sbw_tournament_checkin_policy WHERE tournament_id=?")) {
            policy.setString(1, request.tournamentId);
            try (ResultSet row = policy.executeQuery()) {
                if (!row.next()) return false;
                required = row.getBoolean(1);
            }
        }
        if (!required) return true;
        Set<UUID> checked = new HashSet<UUID>();
        try (PreparedStatement query = c.prepareStatement(
                "SELECT player_uuid FROM sbw_tournament_checkins WHERE match_id=?")) {
            query.setString(1, request.matchId.toString());
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) checked.add(UUID.fromString(row.getString(1)));
            }
        }
        return checked.containsAll(roster);
    }
    void review(UUID game, String reason) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                String tournament = null;
                try (PreparedStatement query = c.prepareStatement(
                        "SELECT tournament_id FROM sbw_tournament_games WHERE game_id=?")) {
                    query.setString(1, game.toString());
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) tournament = row.getString(1);
                    }
                }
                if (tournament == null) { c.commit(); return; }
                try (PreparedStatement owner = c.prepareStatement(
                        "SELECT tournament_id FROM sbw_tournaments"
                        + " WHERE tournament_id=? FOR UPDATE")) {
                    owner.setString(1, tournament); owner.executeQuery().close();
                }
                String match = null;
                try (PreparedStatement query = c.prepareStatement(
                        "SELECT match_id FROM sbw_tournament_games WHERE game_id=?"
                        + " AND state='ALLOCATED' FOR UPDATE")) {
                    query.setString(1, game.toString());
                    try (ResultSet row = query.executeQuery())
                        { if (row.next()) match = row.getString(1); }
                }
                if (match == null) { c.commit(); return; }
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE sbw_tournament_games SET state='REVIEW',result_reason=?,"
                        + "updated_at=? WHERE game_id=? AND state='ALLOCATED'")) {
                    update.setString(1, reason); update.setLong(2, System.currentTimeMillis());
                    update.setString(3, game.toString()); update.executeUpdate();
                }
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE sbw_tournament_matches SET state='REVIEW',updated_at=?"
                        + " WHERE match_id=?")) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, match); update.executeUpdate();
                }
                auditReview(c, tournament, game, reason);
                c.commit();
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    void expire() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT game_id FROM sbw_tournament_games WHERE state='ALLOCATED'"
                + " AND lease_expires<=? LIMIT 32")) {
            query.setLong(1, now);
            List<UUID> games = new ArrayList<UUID>();
            try (ResultSet row = query.executeQuery()) { while (row.next())
                games.add(UUID.fromString(row.getString(1)));
            }
            for (UUID game : games) review(game, "ALLOCATION_TIMEOUT");
        }
        try (Connection c = db.connection(); PreparedStatement delete = c.prepareStatement(
                "DELETE FROM sm_tournament_arena_leases WHERE expires_at<=?")) {
            delete.setLong(1, now); delete.executeUpdate();
        }
        // A vanished backend cannot safely award a win. Give its local result
        // journal five minutes to replay, then hold the game for admin review.
        List<UUID> lost = new ArrayList<UUID>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT g.game_id FROM sbw_tournament_games g"
                + " LEFT JOIN sbw_network_servers s ON s.server_id=g.server_id"
                + " WHERE g.state='RUNNING' AND g.started_at<?"
                + " AND (s.server_id IS NULL OR s.online=0"
                + " OR s.instance_id<>g.server_instance OR s.last_heartbeat<?) LIMIT 32")) {
            query.setLong(1, now - 300000L);
            query.setLong(2, now - settings.serverTimeoutSeconds * 1000L);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) lost.add(UUID.fromString(row.getString(1)));
            }
        }
        for (UUID game : lost) reviewLost(game);
    }

    private void reviewLost(UUID gameId) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                String tournament = null;
                try (PreparedStatement read = c.prepareStatement(
                        "SELECT tournament_id FROM sbw_tournament_games WHERE game_id=?")) {
                    read.setString(1, gameId.toString());
                    try (ResultSet row = read.executeQuery())
                        { if (row.next()) tournament = row.getString(1); }
                }
                if (tournament == null) { c.commit(); return; }
                try (PreparedStatement owner = c.prepareStatement(
                        "SELECT tournament_id FROM sbw_tournaments"
                        + " WHERE tournament_id=? FOR UPDATE")) {
                    owner.setString(1, tournament); owner.executeQuery().close();
                }
                String match = null;
                try (PreparedStatement read = c.prepareStatement(
                        "SELECT match_id FROM sbw_tournament_games"
                        + " WHERE game_id=? AND state='RUNNING' FOR UPDATE")) {
                    read.setString(1, gameId.toString());
                    try (ResultSet row = read.executeQuery())
                        { if (row.next()) match = row.getString(1); }
                }
                if (match == null) { c.commit(); return; }
                long now = System.currentTimeMillis();
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE sbw_tournament_games SET state='REVIEW',"
                        + "result_reason='SERVER_LOST',updated_at=? WHERE game_id=?")) {
                    update.setLong(1, now); update.setString(2, gameId.toString());
                    update.executeUpdate();
                }
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE sbw_tournament_matches SET state='REVIEW',updated_at=?"
                        + " WHERE match_id=? AND state='IN_PROGRESS'")) {
                    update.setLong(1, now); update.setString(2, match);
                    update.executeUpdate();
                }
                auditReview(c, tournament, gameId, "SERVER_LOST");
                c.commit();
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }

    private static void auditReview(Connection c, String tournament, UUID game,
            String reason) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO sbw_tournament_operations VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, tournament); insert.setString(3, "GAME_REVIEW");
            insert.setString(4, "proxy"); insert.setString(5, game + ":" + reason);
            insert.setLong(6, System.currentTimeMillis()); insert.executeUpdate();
        }
    }
}
