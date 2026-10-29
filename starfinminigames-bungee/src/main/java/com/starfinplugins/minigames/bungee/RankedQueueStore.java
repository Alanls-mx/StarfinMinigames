package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent queue and atomic allocation. Every method runs on the bounded proxy I/O pool. */
final class RankedQueueStore {
    static final class Suspended extends IllegalStateException {
        final long untilAt;
        Suspended(long untilAt) { super("Entrada ranqueada suspensa"); this.untilAt = untilAt; }
    }
    private final BungeeDatabase db;
    private final BungeeSettings settings;
    private final UUID proxyId;

    RankedQueueStore(BungeeDatabase db, BungeeSettings settings, UUID proxyId) {
        this.db = db; this.settings = settings; this.proxyId = proxyId;
    }
    static void ensureSchema(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_queue_entries ("
                + "entry_id CHAR(36) NOT NULL PRIMARY KEY,owner_proxy CHAR(36) NOT NULL,"
                + "leader_uuid CHAR(36) NOT NULL,party_id CHAR(36) NULL,party_revision BIGINT NOT NULL,"
                + "mode VARCHAR(16) NOT NULL,context VARCHAR(32) NOT NULL,mmr INT NOT NULL,"
                + "state VARCHAR(16) NOT NULL,joined_at BIGINT NOT NULL,heartbeat_at BIGINT NOT NULL,"
                + "expires_at BIGINT NOT NULL,allocation_id CHAR(36) NULL,"
                + "INDEX idx_sm_ranked_waiting(mode,context,state,joined_at),"
                + "INDEX idx_sm_ranked_owner(owner_proxy,state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_queue_members ("
                + "player_uuid CHAR(36) NOT NULL PRIMARY KEY,entry_id CHAR(36) NOT NULL,"
                + "rating INT NOT NULL,INDEX idx_sm_ranked_member_entry(entry_id))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_allocations ("
                + "allocation_id CHAR(36) NOT NULL PRIMARY KEY,server_id VARCHAR(32) NOT NULL,"
                + "server_instance CHAR(36) NOT NULL,arena_id VARCHAR(32) NOT NULL,"
                + "mode VARCHAR(16) NOT NULL,context VARCHAR(32) NOT NULL,"
                + "state VARCHAR(16) NOT NULL,expected_players INT NOT NULL,"
                + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,"
                + "INDEX idx_sm_ranked_allocation_arena(server_id,arena_id,state))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_allocation_members ("
                + "allocation_id CHAR(36) NOT NULL,player_uuid CHAR(36) NOT NULL,"
                + "entry_id CHAR(36) NOT NULL,reservation_id CHAR(36) NOT NULL,"
                + "team_id VARCHAR(32) NOT NULL,owner_proxy CHAR(36) NOT NULL,"
                + "state VARCHAR(16) NOT NULL,PRIMARY KEY(allocation_id,player_uuid),"
                + "UNIQUE KEY uq_sm_ranked_reservation(reservation_id),"
                + "INDEX idx_sm_ranked_allocation_player(player_uuid))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_arena_leases ("
                + "server_id VARCHAR(32) NOT NULL,arena_id VARCHAR(32) NOT NULL,"
                + "allocation_id CHAR(36) NOT NULL,expires_at BIGINT NOT NULL,"
                + "PRIMARY KEY(server_id,arena_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            sql.execute("CREATE TABLE IF NOT EXISTS sm_ranked_mode_mutex ("
                + "mode VARCHAR(16) NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        }
    }

    static final class Policy {
        final String context;
        final int initial;
        Policy(String context, int initial) { this.context = context; this.initial = initial; }
    }
    Policy policy(NetworkArenaSnapshot arena, long now) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT context,initial_rating FROM sbw_ranked_policy WHERE server_id=?"
                + " AND instance_id=? AND enabled=1 AND updated_at>?")) {
            query.setString(1, arena.getServerId());
            query.setString(2, arena.getInstanceId().toString());
            query.setLong(3, now - settings.serverTimeoutSeconds * 1000L);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Policy(row.getString(1), row.getInt(2)) : null;
            }
        }
    }
    Policy policyFor(String mode) throws SQLException {
        NetworkDirectorySnapshot directory = db.directory();
        Policy selected = null;
        for (NetworkArenaSnapshot arena : NetworkSelector.options(directory, settings.serverNames,
                "bedwars", mode, false, settings.serverTimeoutSeconds * 1000L)) {
            if (!settings.ranked.arenaGroups.contains(arena.getGroup())
                    || arena.getPlayers() != 0 || !"AVAILABLE".equals(arena.getState())) continue;
            Policy policy = policy(arena, System.currentTimeMillis());
            if (policy == null) continue;
            if (selected != null && (!selected.context.equals(policy.context)
                    || selected.initial != policy.initial))
                throw new SQLException("Politicas competitivas divergentes entre servidores");
            selected = policy;
        }
        return selected;
    }
    Map<UUID, Integer> ratings(List<UUID> members, String mode, Policy policy) throws SQLException {
        Map<UUID, Integer> result = new LinkedHashMap<UUID, Integer>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT rating FROM sbw_ranked_profiles WHERE player_uuid=? AND mode=? AND context=?")) {
            query.setQueryTimeout(5);
            for (UUID id : members) {
                query.setString(1, id.toString()); query.setString(2, mode);
                query.setString(3, policy.context);
                try (ResultSet row = query.executeQuery()) {
                    int rating = row.next() ? row.getInt(1) : policy.initial;
                    if (rating < 0 || rating > 1000000) throw new SQLException("Rating competitivo invalido");
                    result.put(id, rating);
                }
            }
        }
        return result;
    }
    static final class Status {
        final UUID entryId, allocationId;
        final String mode, state;
        final int mmr, size;
        final long joinedAt;
        Status(UUID entryId, UUID allocationId, String mode, String state, int mmr, int size, long joinedAt) {
            this.entryId = entryId; this.allocationId = allocationId; this.mode = mode;
            this.state = state; this.mmr = mmr; this.size = size; this.joinedAt = joinedAt;
        }
    }
    static final class OwnedParty {
        final UUID leader, partyId;
        final long revision;
        final List<UUID> members;
        OwnedParty(UUID leader, UUID partyId, long revision, List<UUID> members) {
            this.leader = leader; this.partyId = partyId;
            this.revision = revision; this.members = members;
        }
    }
    List<OwnedParty> ownedParties() throws SQLException {
        List<OwnedParty> result = new ArrayList<OwnedParty>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT entry_id,leader_uuid,party_id,party_revision"
                + " FROM sm_ranked_queue_entries WHERE owner_proxy=? AND state='WAITING'"
                + " AND party_id IS NOT NULL")) {
            query.setString(1, proxyId.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new OwnedParty(UUID.fromString(rows.getString(2)),
                    UUID.fromString(rows.getString(3)), rows.getLong(4),
                    members(c, UUID.fromString(rows.getString(1)))));
            }
        }
        return result;
    }
    Status status(UUID player) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT e.entry_id,e.allocation_id,e.mode,e.state,e.mmr,e.joined_at,"
                + "(SELECT COUNT(*) FROM sm_ranked_queue_members m2 WHERE m2.entry_id=e.entry_id)"
                + " FROM sm_ranked_queue_members m JOIN sm_ranked_queue_entries e"
                + " ON e.entry_id=m.entry_id WHERE m.player_uuid=?")) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Status(UUID.fromString(row.getString(1)),
                    uuid(row.getString(2)), row.getString(3), row.getString(4),
                    row.getInt(5), row.getInt(7), row.getLong(6)) : null;
            }
        }
    }
    Status enqueue(List<UUID> members, UUID leader, UUID party, long revision,
                   String mode, Policy policy, Map<UUID, Integer> ratings) throws SQLException {
        if (members.isEmpty() || new HashSet<UUID>(members).size() != members.size()
                || !members.contains(leader) || !ratings.keySet().containsAll(members))
            throw new IllegalArgumentException("Inscricao ranqueada invalida");
        long now = System.currentTimeMillis();
        UUID entryId = UUID.randomUUID();
        int total = 0;
        for (UUID id : members) total += ratings.get(id);
        int mean = Math.round((float) total / members.size());
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                for (UUID id : members) {
                    try (PreparedStatement suspension = c.prepareStatement(
                            "SELECT until_at FROM sbw_ranked_suspensions"
                            + " WHERE player_uuid=? AND context=? AND until_at>?")) {
                        suspension.setString(1, id.toString()); suspension.setString(2, policy.context);
                        suspension.setLong(3, now);
                        try (ResultSet row = suspension.executeQuery()) {
                            if (row.next()) throw new Suspended(row.getLong(1));
                        }
                    }
                    if (hasActiveRankedMatch(c, id, now))
                        throw new IllegalStateException("Reconexao ranqueada pendente");
                    try (PreparedStatement active = c.prepareStatement(
                            "SELECT state,expires_at FROM sm_network_reservations WHERE player_uuid=? FOR UPDATE")) {
                        active.setString(1, id.toString());
                        try (ResultSet row = active.executeQuery()) {
                            if (row.next() && row.getLong(2) > now
                                    && ("PENDING".equals(row.getString(1)) || "CLAIMED".equals(row.getString(1))))
                                throw new IllegalStateException("Transferencia ativa");
                        }
                    }
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO sm_ranked_queue_entries VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, entryId.toString()); insert.setString(2, proxyId.toString());
                    insert.setString(3, leader.toString()); insert.setString(4, nullable(party));
                    insert.setLong(5, revision); insert.setString(6, mode);
                    insert.setString(7, policy.context); insert.setInt(8, mean);
                    insert.setString(9, "WAITING"); insert.setLong(10, now);
                    insert.setLong(11, now); insert.setLong(12, now + settings.ranked.staleSeconds * 1000L);
                    insert.setString(13, null); insert.executeUpdate();
                }
                try (PreparedStatement member = c.prepareStatement(
                        "INSERT INTO sm_ranked_queue_members(player_uuid,entry_id,rating) VALUES(?,?,?)")) {
                    for (UUID id : members) {
                        member.setString(1, id.toString()); member.setString(2, entryId.toString());
                        member.setInt(3, ratings.get(id)); member.addBatch();
                    }
                    member.executeBatch();
                }
                c.commit();
                return new Status(entryId, null, mode, "WAITING", mean, members.size(), now);
            } catch (SQLException | RuntimeException error) {
                c.rollback(); throw error;
            } finally { c.setAutoCommit(true); }
        }
    }
    private boolean hasActiveRankedMatch(Connection c, UUID player, long now) throws SQLException {
        try (PreparedStatement query = c.prepareStatement(
                "SELECT 1 FROM sm_ranked_allocation_members am"
                + " JOIN sm_ranked_allocations a ON a.allocation_id=am.allocation_id"
                + " JOIN sbw_network_matches m ON m.server_id=a.server_id"
                + " AND m.server_instance=a.server_instance AND m.arena_id=a.arena_id"
                + " WHERE am.player_uuid=? AND a.state='ADMITTED' AND m.state='IN_GAME'"
                + " AND m.ended_at=0 AND m.updated_at>?"
                + " AND m.started_at BETWEEN a.created_at AND a.expires_at+60000 LIMIT 1")) {
            query.setString(1, player.toString()); query.setLong(2, now - 60000L);
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }
    boolean cancel(UUID player, UUID owner) throws SQLException {
        Status status = status(player);
        if (status == null || !"WAITING".equals(status.state)) return false;
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement update = c.prepareStatement(
                        "UPDATE sm_ranked_queue_entries SET state='CANCELLED',expires_at=?"
                        + " WHERE entry_id=? AND owner_proxy=? AND state='WAITING'")) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, status.entryId.toString()); update.setString(3, owner.toString());
                    changed = update.executeUpdate();
                }
                if (changed == 1) deleteMembers(c, status.entryId);
                c.commit(); return changed == 1;
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    void heartbeatOwned(Set<UUID> localPlayers) throws SQLException {
        List<UUID> owned = new ArrayList<UUID>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT entry_id FROM sm_ranked_queue_entries WHERE owner_proxy=? AND state='WAITING'")) {
            query.setString(1, proxyId.toString());
            try (ResultSet rows = query.executeQuery())
                { while (rows.next()) owned.add(UUID.fromString(rows.getString(1))); }
        }
        for (UUID entry : owned) {
            boolean allHere = true;
            try (Connection c = db.connection(); PreparedStatement members = c.prepareStatement(
                    "SELECT player_uuid FROM sm_ranked_queue_members WHERE entry_id=?")) {
                members.setString(1, entry.toString());
                try (ResultSet rows = members.executeQuery()) {
                    while (rows.next()) if (!localPlayers.contains(UUID.fromString(rows.getString(1))))
                        allHere = false;
                }
            }
            if (!allHere) { cancelEntry(entry); continue; }
            try (Connection c = db.connection(); PreparedStatement update = c.prepareStatement(
                    "UPDATE sm_ranked_queue_entries SET heartbeat_at=?,expires_at=?"
                    + " WHERE entry_id=? AND owner_proxy=? AND state='WAITING'")) {
                long now = System.currentTimeMillis(); update.setLong(1, now);
                update.setLong(2, now + settings.ranked.staleSeconds * 1000L);
                update.setString(3, entry.toString()); update.setString(4, proxyId.toString());
                update.executeUpdate();
            }
        }
    }
    private void cancelEntry(UUID entry) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE sm_ranked_queue_entries SET state='CANCELLED'"
                    + " WHERE entry_id=? AND owner_proxy=? AND state='WAITING'")) {
                update.setString(1, entry.toString()); update.setString(2, proxyId.toString());
                if (update.executeUpdate() == 1) deleteMembers(c, entry);
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    private static void deleteMembers(Connection c, UUID entry) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM sm_ranked_queue_members WHERE entry_id=?")) {
            delete.setString(1, entry.toString()); delete.executeUpdate();
        }
    }
    void expire() throws SQLException {
        long now = System.currentTimeMillis();
        List<UUID> expiredAllocations = new ArrayList<UUID>();
        try (Connection c = db.connection(); Statement sql = c.createStatement()) {
            try (PreparedStatement stale = c.prepareStatement(
                    "SELECT entry_id FROM sm_ranked_queue_entries WHERE state='WAITING' AND expires_at<=?")) {
                stale.setLong(1, now);
                List<UUID> ids = new ArrayList<UUID>();
                try (ResultSet rows = stale.executeQuery())
                    { while (rows.next()) ids.add(UUID.fromString(rows.getString(1))); }
                for (UUID id : ids) cancelEntryExpired(id);
            }
            try (PreparedStatement allocations = c.prepareStatement(
                    "SELECT allocation_id FROM sm_ranked_allocations"
                    + " WHERE state IN ('RESERVED','TRANSFERRING') AND expires_at<=?")) {
                allocations.setLong(1, now);
                try (ResultSet rows = allocations.executeQuery())
                    { while (rows.next()) expiredAllocations.add(UUID.fromString(rows.getString(1))); }
            }
            sql.executeUpdate("DELETE FROM sm_ranked_arena_leases WHERE expires_at<=" + now);
        }
        for (UUID id : expiredAllocations) failAllocation(id);
    }
    private void cancelEntryExpired(UUID id) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE sm_ranked_queue_entries SET state='EXPIRED'"
                    + " WHERE entry_id=? AND state='WAITING' AND expires_at<=?")) {
                update.setString(1, id.toString()); update.setLong(2, System.currentTimeMillis());
                if (update.executeUpdate() == 1) deleteMembers(c, id);
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }

    /** One mode mutex, then arena and queue locks, serialize competing proxies. */
    Allocation allocate(String mode) throws SQLException {
        NetworkDirectorySnapshot directory = db.directory();
        long now = System.currentTimeMillis();
        for (NetworkArenaSnapshot arena : NetworkSelector.options(directory, settings.serverNames,
                "bedwars", mode, false, settings.serverTimeoutSeconds * 1000L)) {
            if (!settings.ranked.arenaGroups.contains(arena.getGroup()) || arena.getPlayers() != 0
                    || !"AVAILABLE".equals(arena.getState()) || arena.getMatchId() != null) continue;
            Policy policy = policy(arena, now);
            if (policy == null) continue;
            Allocation result = allocateAt(arena, mode, policy, now);
            if (result != null) return result;
        }
        return null;
    }
    private Allocation allocateAt(NetworkArenaSnapshot arena, String mode, Policy policy, long now)
            throws SQLException {
        try (Connection c = db.connection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                try (PreparedStatement create = c.prepareStatement(
                        "INSERT IGNORE INTO sm_ranked_mode_mutex(mode) VALUES(?)")) {
                    create.setString(1, mode); create.executeUpdate();
                }
                try (PreparedStatement mutex = c.prepareStatement(
                        "SELECT mode FROM sm_ranked_mode_mutex WHERE mode=? FOR UPDATE")) {
                    mutex.setString(1, mode); mutex.executeQuery().close();
                }
                try (PreparedStatement lease = c.prepareStatement(
                        "SELECT expires_at FROM sm_ranked_arena_leases"
                        + " WHERE server_id=? AND arena_id=? FOR UPDATE")) {
                    lease.setString(1, arena.getServerId()); lease.setString(2, arena.getArenaId());
                    try (ResultSet row = lease.executeQuery()) {
                        if (row.next() && row.getLong(1) > now) { c.rollback(); return null; }
                    }
                }
                NetworkArenaSnapshot current = new BedWarsCatalogAdapter().lockArena(c,
                    arena.getServerId(), arena.getArenaId(), settings, now);
                if (current == null || !current.isAvailable() || current.getPlayers() != 0
                        || !"AVAILABLE".equals(current.getState())
                        || !current.getInstanceId().equals(arena.getInstanceId())) {
                    c.rollback(); return null;
                }
                Map<String, Integer> capacities = new TeamSlots(c, arena.getServerId(),
                    arena.getInstanceId().toString(), arena.getArenaId(), now).emptyCapacities();
                if (capacities.size() < 2) { c.rollback(); return null; }
                List<RankedTeamMatcher.Entry> entries = waiting(c, mode, policy.context, now);
                Map<UUID, String> teams = RankedTeamMatcher.assign(entries, capacities, settings.ranked, now);
                if (teams.isEmpty()) { c.rollback(); return null; }
                UUID allocationId = UUID.randomUUID();
                long deadline = now + settings.ranked.allocationSeconds * 1000L;
                int count = 0;
                for (RankedTeamMatcher.Entry e : entries) if (teams.containsKey(e.id)) count += e.size;
                try (PreparedStatement allocation = c.prepareStatement(
                        "INSERT INTO sm_ranked_allocations VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    allocation.setString(1, allocationId.toString());
                    allocation.setString(2, arena.getServerId());
                    allocation.setString(3, arena.getInstanceId().toString());
                    allocation.setString(4, arena.getArenaId()); allocation.setString(5, mode);
                    allocation.setString(6, policy.context); allocation.setString(7, "RESERVED");
                    allocation.setInt(8, count); allocation.setLong(9, now);
                    allocation.setLong(10, deadline); allocation.executeUpdate();
                }
                try (PreparedStatement lease = c.prepareStatement(
                        "INSERT INTO sm_ranked_arena_leases VALUES(?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE allocation_id=VALUES(allocation_id),"
                        + "expires_at=VALUES(expires_at)")) {
                    lease.setString(1, arena.getServerId()); lease.setString(2, arena.getArenaId());
                    lease.setString(3, allocationId.toString()); lease.setLong(4, deadline);
                    lease.executeUpdate();
                }
                List<ReservationSnapshot> reservations = new ArrayList<ReservationSnapshot>();
                for (RankedTeamMatcher.Entry e : entries) {
                    String team = teams.get(e.id);
                    if (team == null) continue;
                    EntryMeta meta = meta(c, e.id);
                    List<UUID> members = members(c, e.id);
                    if (members.size() != e.size) throw new SQLException("Elenco da fila mudou");
                    UUID group = UUID.randomUUID();
                    for (UUID player : members) {
                        ReservationSnapshot old = ReservationJdbc.findForPlayer(c, player, true);
                        if (old != null && old.getExpiresAtMillis() > now
                                && (old.getState() == ReservationState.PENDING
                                    || old.getState() == ReservationState.CLAIMED))
                            throw new SQLException("Jogador ja possui transferencia");
                        ReservationSnapshot reservation = new ReservationSnapshot(UUID.randomUUID(), player,
                            "bedwars", mode, arena.getServerId(), arena.getInstanceId(), arena.getArenaId(),
                            ReservationState.PENDING, now, deadline, group, meta.partyId,
                            meta.leader, meta.revision, members.size(), team, allocationId, policy.context);
                        ReservationJdbc.save(c, reservation, old != null, now);
                        reservations.add(reservation);
                        try (PreparedStatement add = c.prepareStatement(
                                "INSERT INTO sm_ranked_allocation_members VALUES(?,?,?,?,?,?,?)")) {
                            add.setString(1, allocationId.toString()); add.setString(2, player.toString());
                            add.setString(3, e.id.toString()); add.setString(4, reservation.getId().toString());
                            add.setString(5, team); add.setString(6, meta.owner.toString());
                            add.setString(7, "RESERVED"); add.executeUpdate();
                        }
                    }
                    try (PreparedStatement move = c.prepareStatement(
                            "UPDATE sm_ranked_queue_entries SET state='RESERVED',allocation_id=?,expires_at=?"
                            + " WHERE entry_id=? AND state='WAITING'")) {
                        move.setString(1, allocationId.toString()); move.setLong(2, deadline);
                        move.setString(3, e.id.toString());
                        if (move.executeUpdate() != 1) throw new SQLException("Inscricao mudou durante alocacao");
                    }
                }
                c.commit(); return new Allocation(allocationId, arena.getServerId(),
                    arena.getArenaId(), reservations);
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    private List<RankedTeamMatcher.Entry> waiting(Connection c, String mode, String context, long now)
            throws SQLException {
        List<RankedTeamMatcher.Entry> result = new ArrayList<RankedTeamMatcher.Entry>();
        try (PreparedStatement query = c.prepareStatement(
                "SELECT e.entry_id,e.mmr,e.joined_at,COUNT(m.player_uuid) AS members"
                + " FROM sm_ranked_queue_entries e JOIN sm_ranked_queue_members m"
                + " ON m.entry_id=e.entry_id WHERE e.mode=? AND e.context=?"
                + " AND e.state='WAITING' AND e.expires_at>?"
                + " GROUP BY e.entry_id,e.mmr,e.joined_at ORDER BY e.joined_at LIMIT 48")) {
            query.setString(1, mode); query.setString(2, context); query.setLong(3, now);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new RankedTeamMatcher.Entry(
                    UUID.fromString(rows.getString(1)), rows.getInt(2), rows.getInt(4), rows.getLong(3)));
            }
        }
        for (RankedTeamMatcher.Entry entry : result)
            try (PreparedStatement lock = c.prepareStatement(
                    "SELECT entry_id FROM sm_ranked_queue_entries WHERE entry_id=? FOR UPDATE")) {
                lock.setString(1, entry.id.toString()); lock.executeQuery().close();
            }
        return result;
    }
    private static final class EntryMeta {
        final UUID owner, leader, partyId;
        final long revision;
        EntryMeta(UUID owner, UUID leader, UUID partyId, long revision) {
            this.owner = owner; this.leader = leader; this.partyId = partyId; this.revision = revision;
        }
    }
    private static EntryMeta meta(Connection c, UUID entry) throws SQLException {
        try (PreparedStatement query = c.prepareStatement(
                "SELECT owner_proxy,leader_uuid,party_id,party_revision FROM sm_ranked_queue_entries"
                + " WHERE entry_id=?")) {
            query.setString(1, entry.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Inscricao ausente");
                return new EntryMeta(UUID.fromString(row.getString(1)), UUID.fromString(row.getString(2)),
                    uuid(row.getString(3)), row.getLong(4));
            }
        }
    }
    private static List<UUID> members(Connection c, UUID entry) throws SQLException {
        List<UUID> result = new ArrayList<UUID>();
        try (PreparedStatement query = c.prepareStatement(
                "SELECT player_uuid FROM sm_ranked_queue_members WHERE entry_id=? ORDER BY player_uuid")) {
            query.setString(1, entry.toString());
            try (ResultSet rows = query.executeQuery())
                { while (rows.next()) result.add(UUID.fromString(rows.getString(1))); }
        }
        return result;
    }
    static final class Allocation {
        final UUID id;
        final String serverId, arenaId;
        final List<ReservationSnapshot> reservations;
        Allocation(UUID id, String serverId, String arenaId, List<ReservationSnapshot> reservations) {
            this.id = id; this.serverId = serverId; this.arenaId = arenaId;
            this.reservations = Collections.unmodifiableList(reservations);
        }
    }
    List<Status> ownedReady() throws SQLException {
        List<Status> ready = new ArrayList<Status>();
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT entry_id,allocation_id,mode,state,mmr,joined_at FROM sm_ranked_queue_entries"
                + " WHERE owner_proxy=? AND state='RESERVED'")) {
            query.setString(1, proxyId.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) ready.add(new Status(UUID.fromString(rows.getString(1)),
                    uuid(rows.getString(2)), rows.getString(3), rows.getString(4),
                    rows.getInt(5), 0, rows.getLong(6)));
            }
        }
        return ready;
    }
    List<ReservationSnapshot> claimTransfers(Status entry) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement move = c.prepareStatement(
                        "UPDATE sm_ranked_queue_entries SET state='TRANSFERRING'"
                        + " WHERE entry_id=? AND owner_proxy=? AND state='RESERVED' AND expires_at>?")) {
                    move.setString(1, entry.entryId.toString()); move.setString(2, proxyId.toString());
                    move.setLong(3, System.currentTimeMillis());
                    if (move.executeUpdate() != 1) { c.rollback(); return Collections.emptyList(); }
                }
                List<ReservationSnapshot> rows = new ArrayList<ReservationSnapshot>();
                try (PreparedStatement query = c.prepareStatement(
                        "SELECT r.reservation_id,r.player_uuid,r.minigame_id,r.mode,r.server_id,"
                        + "r.server_instance,r.arena_id,r.state,r.created_at,r.expires_at,"
                        + "r.group_id,r.party_id,r.leader_uuid,r.party_revision,r.group_size,r.team_id,"
                        + "r.ranked_allocation_id,r.competitive_context FROM sm_network_reservations r"
                        + " JOIN sm_ranked_allocation_members am ON am.reservation_id=r.reservation_id"
                        + " WHERE am.entry_id=? ORDER BY r.player_uuid")) {
                    query.setString(1, entry.entryId.toString());
                    try (ResultSet found = query.executeQuery())
                        { while (found.next()) rows.add(ReservationJdbc.read(found)); }
                }
                if (rows.isEmpty()) throw new SQLException("Alocacao sem reservas");
                c.commit(); return rows;
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    void failAllocation(UUID id) throws SQLException {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement allocation = c.prepareStatement(
                        "UPDATE sm_ranked_allocations SET state='CANCELLED'"
                        + " WHERE allocation_id=? AND state IN ('RESERVED','TRANSFERRING')")) {
                    allocation.setString(1, id.toString()); changed = allocation.executeUpdate();
                }
                if (changed != 1) { c.rollback(); return; }
                try (PreparedStatement reservations = c.prepareStatement(
                        "UPDATE sm_network_reservations SET state='FAILED'"
                        + " WHERE ranked_allocation_id=? AND state IN ('PENDING','CLAIMED')")) {
                    reservations.setString(1, id.toString()); reservations.executeUpdate();
                }
                try (PreparedStatement entries = c.prepareStatement(
                        "UPDATE sm_ranked_queue_entries SET state='CANCELLED'"
                        + " WHERE allocation_id=? AND state IN ('RESERVED','TRANSFERRING')")) {
                    entries.setString(1, id.toString()); entries.executeUpdate();
                }
                try (PreparedStatement drop = c.prepareStatement(
                        "DELETE FROM sm_ranked_queue_members WHERE entry_id IN"
                        + " (SELECT entry_id FROM sm_ranked_queue_entries WHERE allocation_id=?)")) {
                    drop.setString(1, id.toString()); drop.executeUpdate();
                }
                try (PreparedStatement lease = c.prepareStatement(
                        "DELETE FROM sm_ranked_arena_leases WHERE allocation_id=?")) {
                    lease.setString(1, id.toString()); lease.executeUpdate();
                }
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(true); }
        }
    }
    int waitingCount() throws SQLException {
        try (Connection c = db.connection(); PreparedStatement query = c.prepareStatement(
                "SELECT COUNT(*) FROM sm_ranked_queue_entries WHERE state='WAITING' AND expires_at>?")) {
            query.setLong(1, System.currentTimeMillis());
            try (ResultSet row = query.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }
    List<String> diagnostics(String topic, String player) throws SQLException {
        List<String> lines = new ArrayList<String>();
        String query;
        if ("queues".equals(topic))
            query = "SELECT mode,context,COUNT(*),SUM(CASE WHEN party_id IS NULL THEN 0 ELSE 1 END)"
                + " FROM sm_ranked_queue_entries WHERE state='WAITING' AND expires_at>?"
                + " GROUP BY mode,context ORDER BY mode,context";
        else if ("players".equals(topic))
            query = "SELECT e.mode,e.state,m.player_uuid,m.rating FROM sm_ranked_queue_members m"
                + " JOIN sm_ranked_queue_entries e ON e.entry_id=m.entry_id"
                + " WHERE e.expires_at>? ORDER BY e.joined_at LIMIT 20";
        else if ("matches".equals(topic))
            query = "SELECT arena_id,mode,state,expected_players,server_id"
                + " FROM sm_ranked_allocations WHERE expires_at>?"
                + " ORDER BY created_at DESC LIMIT 20";
        else if ("inspect".equals(topic) || "opponents".equals(topic)) {
            UUID id;
            try { id = UUID.fromString(player); }
            catch (IllegalArgumentException invalid) { return Collections.singletonList(
                "Use o UUID do jogador para inspecionar a fila."); }
            if ("inspect".equals(topic)) {
                Status status = status(id);
                if (status == null) return Collections.singletonList("Jogador sem inscricao ativa.");
                return Collections.singletonList(status.mode + " | " + status.state + " | MMR "
                    + status.mmr + " | grupo " + status.size + " | inscricao " + status.entryId);
            }
            try (Connection c = db.connection(); PreparedStatement recent = c.prepareStatement(
                    "SELECT other.player_uuid,COUNT(DISTINCT other.match_id) FROM sbw_ranked_results mine"
                    + " JOIN sbw_ranked_results other ON other.match_id=mine.match_id"
                    + " WHERE mine.player_uuid=? AND other.player_uuid<>?"
                    + " AND other.team_id<>mine.team_id AND mine.completed_at>?"
                    + " GROUP BY other.player_uuid ORDER BY COUNT(DISTINCT other.match_id) DESC LIMIT 10")) {
                recent.setString(1, id.toString()); recent.setString(2, id.toString());
                recent.setLong(3, System.currentTimeMillis() - 7L * 86400000L);
                try (ResultSet row = recent.executeQuery()) {
                    while (row.next()) lines.add(row.getString(1) + ": " + row.getInt(2) + " confronto(s)");
                }
            }
            if (lines.isEmpty()) lines.add("Sem confrontos nos ultimos 7 dias.");
            return lines;
        } else return Collections.singletonList("Diagnostico desconhecido.");
        try (Connection c = db.connection(); PreparedStatement statement = c.prepareStatement(query)) {
            statement.setLong(1, System.currentTimeMillis()); statement.setQueryTimeout(5);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if ("queues".equals(topic)) lines.add(row.getString(1) + " / " + row.getString(2)
                        + ": " + row.getInt(3) + " inscricoes, " + row.getInt(4) + " Parties");
                    else if ("players".equals(topic)) lines.add(row.getString(3) + " | "
                        + row.getString(1) + " | " + row.getString(2) + " | MMR " + row.getInt(4));
                    else lines.add(row.getString(1) + " / " + row.getString(2) + " | "
                        + row.getString(3) + " | " + row.getInt(4) + " jogadores | " + row.getString(5));
                }
            }
        }
        if (lines.isEmpty()) lines.add("Nenhum registro ativo.");
        return lines;
    }
    private static String nullable(UUID id) { return id == null ? null : id.toString(); }
    private static UUID uuid(String text) { return text == null ? null : UUID.fromString(text); }
}
