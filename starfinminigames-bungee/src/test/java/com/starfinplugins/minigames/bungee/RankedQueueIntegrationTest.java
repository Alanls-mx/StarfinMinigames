package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.ReservationSnapshot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.md_5.bungee.config.Configuration;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in only: SM_TEST_MYSQL_DATABASE must name an explicitly disposable schema. */
public class RankedQueueIntegrationTest {
    @Test public void queueAllocationIsAtomicAndCasualCannotConsumeRankedArena() throws Exception {
        String host = System.getenv("SM_TEST_MYSQL_HOST");
        String schema = System.getenv("SM_TEST_MYSQL_DATABASE");
        String user = System.getenv("SM_TEST_MYSQL_USER");
        Assume.assumeTrue(schema != null && schema.startsWith("sbw_ranked_test_")
            && host != null && user != null);
        int port = Integer.parseInt(System.getenv("SM_TEST_MYSQL_PORT"));
        String password = System.getenv("SM_TEST_MYSQL_PASSWORD");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        String server = "r62-" + UUID.randomUUID().toString().substring(0, 8);
        UUID instance = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement sql = c.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_servers (server_id VARCHAR(32) PRIMARY KEY,"
                + "instance_id CHAR(36),online TINYINT,online_players INT,max_players INT,last_heartbeat BIGINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_arenas (server_id VARCHAR(32),"
                + "arena_id VARCHAR(32),configuration_yaml MEDIUMTEXT,mode VARCHAR(16),arena_group VARCHAR(32),"
                + "state VARCHAR(16),match_id CHAR(36),players INT,max_players INT,enabled TINYINT,"
                + "editing TINYINT,joinable TINYINT,updated_at BIGINT,deleted_at BIGINT,"
                + "PRIMARY KEY(server_id,arena_id))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_players (player_uuid CHAR(36) PRIMARY KEY,"
                + "server_id VARCHAR(32),server_instance CHAR(36),arena_id VARCHAR(32),"
                + "team_id VARCHAR(32),online TINYINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_matches (match_id CHAR(36) PRIMARY KEY,"
                + "server_id VARCHAR(32),server_instance CHAR(36),arena_id VARCHAR(32),"
                + "state VARCHAR(16),started_at BIGINT,ended_at BIGINT,updated_at BIGINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_ranked_policy (server_id VARCHAR(32) PRIMARY KEY,"
                + "instance_id CHAR(36),enabled TINYINT,context VARCHAR(32),"
                + "initial_rating INT,updated_at BIGINT,season_version BIGINT DEFAULT 0)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_ranked_season_control"
                + " (control_id TINYINT PRIMARY KEY,active_id VARCHAR(32),policy_version BIGINT)");
            sql.execute("INSERT IGNORE INTO sbw_ranked_season_control VALUES(1,NULL,0)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_ranked_seasons"
                + " (season_id VARCHAR(32) PRIMARY KEY,state VARCHAR(16),starts_at BIGINT,"
                + "ends_at BIGINT,modes VARCHAR(96))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_ranked_profiles (player_uuid CHAR(36),"
                + "mode VARCHAR(16),context VARCHAR(32),rating INT,PRIMARY KEY(player_uuid,mode,context))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_ranked_suspensions (player_uuid CHAR(36),"
                + "context VARCHAR(32),until_at BIGINT,reason VARCHAR(32),updated_at BIGINT,"
                + "PRIMARY KEY(player_uuid,context))");
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_network_servers VALUES(?,?,?,?,?,?)")) {
                insert.setString(1, server); insert.setString(2, instance.toString());
                insert.setBoolean(3, true); insert.setInt(4, 0); insert.setInt(5, 20);
                insert.setLong(6, now); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, server); insert.setString(2, "ranked_duel");
                insert.setString(3, "teams:\n  red:\n    capacity: 1\n  blue:\n    capacity: 1\n");
                insert.setString(4, "SOLO"); insert.setString(5, "ranked");
                insert.setString(6, "AVAILABLE"); insert.setString(7, null);
                insert.setInt(8, 0); insert.setInt(9, 2);
                insert.setBoolean(10, true); insert.setBoolean(11, false);
                insert.setBoolean(12, true); insert.setLong(13, now);
                insert.setLong(14, 0); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_ranked_policy(server_id,instance_id,enabled,context,"
                    + "initial_rating,updated_at,season_version) VALUES(?,?,?,?,?,?,0)")) {
                insert.setString(1, server); insert.setString(2, instance.toString());
                insert.setBoolean(3, true); insert.setString(4, "preseason-1");
                insert.setInt(5, 1000); insert.setLong(6, now); insert.executeUpdate();
            }
        }
        Configuration yaml = new Configuration();
        yaml.set("network.enabled", true); yaml.set("network.servers." + server, "survival");
        yaml.set("mysql.enabled", true); yaml.set("mysql.host", host); yaml.set("mysql.port", port);
        yaml.set("mysql.database", schema); yaml.set("mysql.username", user);
        yaml.set("mysql.password", password); yaml.set("mysql.ssl-mode", "DISABLED");
        yaml.set("ranked-matchmaking.enabled", true);
        BungeeSettings settings = new BungeeSettings(yaml, name -> true);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID firstProxy = UUID.randomUUID(), secondProxy = UUID.randomUUID();
        try (BungeeDatabase db = new BungeeDatabase(settings)) {
            RankedQueueStore first = new RankedQueueStore(db, settings, firstProxy);
            RankedQueueStore second = new RankedQueueStore(db, settings, secondProxy);
            RankedQueueStore.Policy policy = first.policyFor("solo");
            assertNotNull(policy);
            assertEquals(1000, policy.initial);
            Map<UUID, Integer> ra = first.ratings(Collections.singletonList(a), "solo", policy);
            Map<UUID, Integer> rb = first.ratings(Collections.singletonList(b), "solo", policy);
            first.enqueue(Collections.singletonList(a), a, null, 0L, "solo", policy, ra);
            try {
                second.enqueue(Collections.singletonList(a), a, null, 0L, "solo", policy, ra);
                fail("A second proxy cannot enroll the same player");
            } catch (java.sql.SQLException expected) { /* unique player row */ }
            second.enqueue(Collections.singletonList(b), b, null, 0L, "solo", policy, rb);
            assertNull("casual selection must skip ranked arena", db.reserve(UUID.randomUUID(), "bedwars", "solo"));
            ExecutorService racers = Executors.newFixedThreadPool(2);
            RankedQueueStore.Allocation allocation;
            try {
                Future<RankedQueueStore.Allocation> one = racers.submit(() -> first.allocate("solo"));
                Future<RankedQueueStore.Allocation> two = racers.submit(() -> second.allocate("solo"));
                RankedQueueStore.Allocation left = one.get(), right = two.get();
                assertTrue("Exactly one proxy allocates the arena", (left == null) != (right == null));
                allocation = left == null ? right : left;
            } finally { racers.shutdownNow(); }
            assertNotNull(allocation);
            assertEquals(2, allocation.reservations.size());
            assertNull(second.allocate("solo"));
            for (ReservationSnapshot row : allocation.reservations) {
                assertTrue(row.isRanked());
                assertEquals(allocation.id, row.getRankedAllocationId());
                assertEquals("preseason-1", row.getCompetitiveContext());
            }
            assertNotEquals(allocation.reservations.get(0).getTeamId(),
                allocation.reservations.get(1).getTeamId());
            first.failAllocation(allocation.id);
            try (Connection c = DriverManager.getConnection(url, user, password);
                 PreparedStatement query = c.prepareStatement(
                    "SELECT COUNT(*) FROM sm_ranked_queue_members WHERE player_uuid IN (?,?)")) {
                query.setString(1, a.toString()); query.setString(2, b.toString());
                try (ResultSet row = query.executeQuery()) { row.next(); assertEquals(0, row.getInt(1)); }
            }
            try (Connection c = DriverManager.getConnection(url, user, password);
                 Statement sql = c.createStatement()) {
                sql.executeUpdate("UPDATE sm_ranked_allocations SET state='ADMITTED' WHERE allocation_id='"
                    + allocation.id + "'");
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO sbw_network_matches VALUES(?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, server);
                    insert.setString(3, instance.toString()); insert.setString(4, "ranked_duel");
                    insert.setString(5, "IN_GAME"); insert.setLong(6, now + 5000L);
                    insert.setLong(7, 0L); insert.setLong(8, System.currentTimeMillis());
                    insert.executeUpdate();
                }
            }
            try {
                first.enqueue(Collections.singletonList(a), a, null, 0L, "solo", policy, ra);
                fail("A disconnected ranked player must return to the current match");
            } catch (IllegalStateException expected) { /* live match catalog */ }
            try (Connection c = DriverManager.getConnection(url, user, password);
                 PreparedStatement closed = c.prepareStatement(
                    "UPDATE sbw_network_matches SET state='CLOSED',ended_at=? WHERE server_id=?")) {
                closed.setLong(1, System.currentTimeMillis()); closed.setString(2, server);
                closed.executeUpdate();
            }
            first.enqueue(Collections.singletonList(a), a, null, 0L, "solo", policy, ra);
            assertTrue(first.cancel(a, firstProxy));
            try (Connection c = DriverManager.getConnection(url, user, password);
                 PreparedStatement block = c.prepareStatement(
                    "INSERT INTO sbw_ranked_suspensions VALUES(?, ?, ?, 'REPEATED_ABANDONMENT', ?)")) {
                block.setString(1, b.toString()); block.setString(2, policy.context);
                block.setLong(3, System.currentTimeMillis() + 60000L);
                block.setLong(4, System.currentTimeMillis()); block.executeUpdate();
            }
            try {
                second.enqueue(Collections.singletonList(b), b, null, 0L, "solo", policy, rb);
                fail("Suspended player must not queue");
            } catch (RankedQueueStore.Suspended expected) { assertTrue(expected.untilAt > now); }
            try (Connection c = DriverManager.getConnection(url, user, password);
                 Statement sql = c.createStatement()) {
                sql.executeUpdate("INSERT INTO sbw_ranked_seasons VALUES('season-test','ACTIVE',"
                    + (now - 1000) + "," + (now + 120000) + ",'solo')");
                sql.executeUpdate("UPDATE sbw_ranked_season_control SET active_id='season-test',"
                    + "policy_version=1 WHERE control_id=1");
            }
            assertNull("Old policy cannot queue after season switch", first.policyFor("solo"));
            UUID stalePlayer = UUID.randomUUID();
            try {
                first.enqueue(Collections.singletonList(stalePlayer), stalePlayer,
                    null, 0L, "solo", policy, Collections.singletonMap(stalePlayer, 1000));
                fail("Stale context must be rejected");
            } catch (IllegalStateException expected) { /* central version changed */ }
            try (Connection c = DriverManager.getConnection(url, user, password);
                 PreparedStatement update = c.prepareStatement(
                    "UPDATE sbw_ranked_policy SET context='season-test',season_version=1,"
                    + "updated_at=? WHERE server_id=?")) {
                update.setLong(1, System.currentTimeMillis()); update.setString(2, server);
                update.executeUpdate();
            }
            assertNotNull("Updated backend policy enables season", first.policyFor("solo"));
            try (Connection c = DriverManager.getConnection(url, user, password);
                 Statement sql = c.createStatement()) {
                sql.executeUpdate("UPDATE sbw_ranked_seasons SET state='CLOSING'"
                    + " WHERE season_id='season-test'");
                sql.executeUpdate("UPDATE sbw_ranked_season_control SET policy_version=2"
                    + " WHERE control_id=1");
            }
            assertNull("CLOSING closes the queue immediately", first.policyFor("solo"));
        } finally {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                for (String table : Arrays.asList("sm_ranked_arena_leases", "sm_ranked_allocations",
                        "sbw_ranked_policy", "sbw_network_matches", "sbw_network_arenas", "sbw_network_servers")) {
                    try (PreparedStatement delete = c.prepareStatement(
                            "DELETE FROM " + table + " WHERE server_id=?")) {
                        delete.setString(1, server); delete.executeUpdate();
                    }
                }
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM sm_network_reservations WHERE player_uuid IN (?,?)")) {
                    delete.setString(1, a.toString()); delete.setString(2, b.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM sbw_ranked_suspensions WHERE player_uuid IN (?,?)")) {
                    delete.setString(1, a.toString()); delete.setString(2, b.toString());
                    delete.executeUpdate();
                }
                try (Statement reset = c.createStatement()) {
                    reset.executeUpdate("UPDATE sbw_ranked_season_control SET active_id=NULL,"
                        + "policy_version=0 WHERE control_id=1");
                    reset.executeUpdate("DELETE FROM sbw_ranked_seasons WHERE season_id='season-test'");
                }
            }
        }
    }
}
