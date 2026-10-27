package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.ReservationJdbc;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.minigames.api.ReservationState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.config.Configuration;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs only against an explicitly configured disposable MySQL/MariaDB schema. */
public class BungeeDatabaseIntegrationTest {
    @Test public void concurrentReservationsRespectLastSlotAndClaimRequiresCorrectInstance() throws Exception {
        String host = System.getenv("SM_TEST_MYSQL_HOST");
        String port = System.getenv("SM_TEST_MYSQL_PORT");
        String schema = System.getenv("SM_TEST_MYSQL_DATABASE");
        String user = System.getenv("SM_TEST_MYSQL_USER");
        String password = System.getenv("SM_TEST_MYSQL_PASSWORD");
        Assume.assumeTrue("requires disposable MySQL schema", host != null && schema != null && user != null);
        int mysqlPort = port == null ? 3306 : Integer.parseInt(port);
        String url = "jdbc:mysql://" + host + ":" + mysqlPort + "/" + schema
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        String serverId = "test-" + UUID.randomUUID().toString().substring(0, 8);
        UUID instance = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sbw_network_servers ("
                + "server_id VARCHAR(32) PRIMARY KEY,instance_id CHAR(36),online TINYINT,"
                + "online_players INT,max_players INT,last_heartbeat BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS sbw_network_arenas ("
                + "server_id VARCHAR(32),arena_id VARCHAR(32),configuration_yaml MEDIUMTEXT,mode VARCHAR(16),arena_group VARCHAR(32),"
                + "state VARCHAR(16),match_id CHAR(36) NULL,players INT,max_players INT,"
                + "enabled TINYINT,editing TINYINT,joinable TINYINT,updated_at BIGINT,"
                + "deleted_at BIGINT,PRIMARY KEY(server_id,arena_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS sbw_network_players ("
                + "player_uuid CHAR(36) PRIMARY KEY,server_id VARCHAR(32),server_instance CHAR(36),"
                + "arena_id VARCHAR(32),team_id VARCHAR(32),online TINYINT)");
            // Simulate the pre-4.4.1 schema; startup must migrate it without deleting rows.
            statement.execute("CREATE TABLE IF NOT EXISTS sm_network_reservations ("
                + "reservation_id CHAR(36) NOT NULL PRIMARY KEY,player_uuid CHAR(36) NOT NULL UNIQUE,"
                + "minigame_id VARCHAR(32) NOT NULL,mode VARCHAR(16) NOT NULL,"
                + "server_id VARCHAR(32) NOT NULL,server_instance CHAR(36) NOT NULL,"
                + "arena_id VARCHAR(32) NOT NULL,state VARCHAR(16) NOT NULL,"
                + "created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_servers VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, serverId); insert.setString(2, instance.toString());
                insert.setBoolean(3, true); insert.setInt(4, 0); insert.setInt(5, 20);
                insert.setLong(6, now); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, serverId); insert.setString(2, "duel");
                insert.setString(3, "teams:\n  red:\n    capacity: 1\n  blue:\n    capacity: 1\n");
                insert.setString(4, "SOLO"); insert.setString(5, "default");
                insert.setString(6, "AVAILABLE"); insert.setString(7, null);
                insert.setInt(8, 0); insert.setInt(9, 1);
                insert.setBoolean(10, true); insert.setBoolean(11, false);
                insert.setBoolean(12, true); insert.setLong(13, now);
                insert.setLong(14, 0); insert.executeUpdate();
            }
        }
        Configuration yaml = new Configuration();
        yaml.set("network.enabled", true);
        yaml.set("network.servers." + serverId, "survival");
        yaml.set("mysql.enabled", true);
        yaml.set("mysql.host", host);
        yaml.set("mysql.port", mysqlPort);
        yaml.set("mysql.database", schema);
        yaml.set("mysql.username", user);
        yaml.set("mysql.password", password == null ? "" : password);
        yaml.set("mysql.ssl-mode", "DISABLED");
        BungeeSettings settings = new BungeeSettings(yaml, name -> true);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ReservationSnapshot winner = null;
        List<ReservationSnapshot> partyRows = java.util.Collections.emptyList();
        List<ReservationSnapshot> duoRows = new java.util.ArrayList<ReservationSnapshot>();
        List<ReservationSnapshot> concurrentRows = new java.util.ArrayList<ReservationSnapshot>();
        try (BungeeDatabase database = new BungeeDatabase(settings)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<ReservationSnapshot> first = workers.submit(() -> {
                start.await(); return database.reserve(UUID.randomUUID(), "bedwars", "solo");
            });
            Future<ReservationSnapshot> second = workers.submit(() -> {
                start.await(); return database.reserve(UUID.randomUUID(), "bedwars", "solo");
            });
            start.countDown();
            ReservationSnapshot one = first.get(15, TimeUnit.SECONDS);
            ReservationSnapshot two = second.get(15, TimeUnit.SECONDS);
            assertTrue("exactly one reservation wins the final slot", (one == null) != (two == null));
            winner = one == null ? two : one;
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                assertNull(ReservationJdbc.claim(connection, winner.getPlayerId(), serverId,
                    UUID.randomUUID(), System.currentTimeMillis(), 90000));
                ReservationSnapshot claimed = ReservationJdbc.claim(connection, winner.getPlayerId(),
                    serverId, instance, System.currentTimeMillis(), 90000);
                assertNotNull(claimed);
                assertEquals(ReservationState.CLAIMED, claimed.getState());
                assertTrue(ReservationJdbc.finish(connection, winner.getId(), winner.getPlayerId(),
                    ReservationState.CONFIRMED, System.currentTimeMillis()));
                assertFalse(ReservationJdbc.finish(connection, winner.getId(), winner.getPlayerId(),
                    ReservationState.CONFIRMED, System.currentTimeMillis()));
            }
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,serverId); insert.setString(2,"party");
                insert.setString(3,"teams:\n  red:\n    capacity: 1\n  blue:\n    capacity: 1\n");
                insert.setString(4,"SOLO"); insert.setString(5,"default");
                insert.setString(6,"AVAILABLE"); insert.setString(7,null);
                insert.setInt(8,0); insert.setInt(9,2);
                insert.setBoolean(10,true); insert.setBoolean(11,false);
                insert.setBoolean(12,true); insert.setLong(13,System.currentTimeMillis());
                insert.setLong(14,0); insert.executeUpdate();
            }
            UUID memberA = UUID.randomUUID(), memberB = UUID.randomUUID(), memberC = UUID.randomUUID();
            assertTrue("no partial Party reservation if capacity is too small",
                database.reserveGroup(Arrays.asList(memberA,memberB,memberC),"bedwars","solo").isEmpty());
            try (Connection connection = DriverManager.getConnection(url,user,password);
                 PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM sm_network_reservations WHERE player_uuid IN (?,?,?)")) {
                count.setString(1,memberA.toString()); count.setString(2,memberB.toString());
                count.setString(3,memberC.toString());
                try (java.sql.ResultSet rows = count.executeQuery()) {
                    rows.next(); assertEquals(0,rows.getInt(1));
                }
            }
            partyRows = database.reserveGroup(Arrays.asList(memberA,memberB),"bedwars","solo");
            assertEquals(2,partyRows.size());
            assertEquals("party",partyRows.get(0).getArenaId());
            assertEquals(partyRows.get(0).getArenaId(),partyRows.get(1).getArenaId());
            assertEquals(partyRows.get(0).getServerInstance(),partyRows.get(1).getServerInstance());
            assertEquals(partyRows.get(0).getGroupId(), partyRows.get(1).getGroupId());
            assertNotEquals(partyRows.get(0).getTeamId(), partyRows.get(1).getTeamId());
            try (Connection connection = DriverManager.getConnection(url,user,password);
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,serverId); insert.setString(2,"duo");
                insert.setString(3,"teams:\n  red:\n    capacity: 2\n  blue:\n    capacity: 2\n");
                insert.setString(4,"DUO"); insert.setString(5,"default");
                insert.setString(6,"AVAILABLE"); insert.setString(7,null);
                insert.setInt(8,0); insert.setInt(9,4);
                insert.setBoolean(10,true); insert.setBoolean(11,false);
                insert.setBoolean(12,true); insert.setLong(13,System.currentTimeMillis());
                insert.setLong(14,0); insert.executeUpdate();
            }
            UUID firstParty = UUID.randomUUID(), secondParty = UUID.randomUUID();
            assertTrue("three members do not fit a team of two",
                database.reserveGroup(Arrays.asList(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                    "bedwars", "duo").isEmpty());
            duoRows.addAll(database.reserveGroup(Arrays.asList(UUID.randomUUID(), UUID.randomUUID()),
                "bedwars", "duo", firstParty, 1L, UUID.randomUUID()));
            duoRows.addAll(database.reserveGroup(Arrays.asList(UUID.randomUUID(), UUID.randomUUID()),
                "bedwars", "duo", secondParty, 1L, UUID.randomUUID()));
            assertEquals(4, duoRows.size());
            assertEquals(duoRows.get(0).getTeamId(), duoRows.get(1).getTeamId());
            assertEquals(duoRows.get(2).getTeamId(), duoRows.get(3).getTeamId());
            assertNotEquals(duoRows.get(0).getTeamId(), duoRows.get(2).getTeamId());
            assertEquals(firstParty, duoRows.get(0).getPartyId());
            assertNull(database.reserve(UUID.randomUUID(), "bedwars", "duo"));
            assertTrue(database.reserveGroup(Arrays.asList(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                "bedwars", "duo").isEmpty());
            try (Connection connection = DriverManager.getConnection(url,user,password)) {
                for (int i = 0; i < 2; i++) {
                    ReservationSnapshot row = duoRows.get(i);
                    assertNotNull(ReservationJdbc.claim(connection,row.getPlayerId(),serverId,instance,
                        System.currentTimeMillis(),90000));
                    assertTrue(ReservationJdbc.finish(connection,row.getId(),row.getPlayerId(),
                        ReservationState.CONFIRMED,System.currentTimeMillis()));
                    try (PreparedStatement offline = connection.prepareStatement(
                            "INSERT INTO sbw_network_players VALUES (?,?,?,?,?,0)")) {
                        offline.setString(1,row.getPlayerId().toString());
                        offline.setString(2,serverId);
                        offline.setString(3,instance.toString());
                        offline.setString(4,"duo");
                        offline.setString(5,row.getTeamId());
                        offline.executeUpdate();
                    }
                }
            }
            List<ReservationSnapshot> replacement = database.reserveGroup(
                Arrays.asList(UUID.randomUUID(), UUID.randomUUID()),"bedwars","duo");
            assertEquals(2,replacement.size());
            assertEquals(duoRows.get(0).getTeamId(),replacement.get(0).getTeamId());
            duoRows.addAll(replacement);
            try (Connection connection = DriverManager.getConnection(url,user,password);
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,serverId); insert.setString(2,"duo_concurrent");
                insert.setString(3,"teams:\n  red:\n    capacity: 2\n  blue:\n    capacity: 2\n");
                insert.setString(4,"DUO"); insert.setString(5,"default");
                insert.setString(6,"AVAILABLE"); insert.setString(7,null);
                insert.setInt(8,0); insert.setInt(9,4);
                insert.setBoolean(10,true); insert.setBoolean(11,false);
                insert.setBoolean(12,true); insert.setLong(13,System.currentTimeMillis());
                insert.setLong(14,0); insert.executeUpdate();
            }
            try (BungeeDatabase secondProxy = new BungeeDatabase(settings)) {
                CountDownLatch together = new CountDownLatch(1);
                Future<List<ReservationSnapshot>> a = workers.submit(() -> {
                    together.await(); return database.reserveGroup(
                        Arrays.asList(UUID.randomUUID(),UUID.randomUUID()),"bedwars","duo");
                });
                Future<List<ReservationSnapshot>> b = workers.submit(() -> {
                    together.await(); return secondProxy.reserveGroup(
                        Arrays.asList(UUID.randomUUID(),UUID.randomUUID()),"bedwars","duo");
                });
                together.countDown();
                List<ReservationSnapshot> firstGroup = a.get(15,TimeUnit.SECONDS);
                List<ReservationSnapshot> secondGroup = b.get(15,TimeUnit.SECONDS);
                assertEquals(2,firstGroup.size()); assertEquals(2,secondGroup.size());
                assertEquals("duo_concurrent",firstGroup.get(0).getArenaId());
                assertEquals("duo_concurrent",secondGroup.get(0).getArenaId());
                assertNotEquals(firstGroup.get(0).getTeamId(),secondGroup.get(0).getTeamId());
                concurrentRows.addAll(firstGroup); concurrentRows.addAll(secondGroup);
            }
        } finally {
            workers.shutdownNow();
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                if (winner != null) try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM sm_network_reservations WHERE reservation_id=?")) {
                    cleanup.setString(1, winner.getId().toString()); cleanup.executeUpdate();
                }
                for (ReservationSnapshot row : partyRows)
                    try (PreparedStatement cleanup = connection.prepareStatement(
                            "DELETE FROM sm_network_reservations WHERE reservation_id=?")) {
                        cleanup.setString(1,row.getId().toString()); cleanup.executeUpdate();
                    }
                for (ReservationSnapshot row : duoRows)
                    try (PreparedStatement cleanup = connection.prepareStatement(
                            "DELETE FROM sm_network_reservations WHERE reservation_id=?")) {
                        cleanup.setString(1,row.getId().toString()); cleanup.executeUpdate();
                    }
                for (ReservationSnapshot row : concurrentRows)
                    try (PreparedStatement cleanup = connection.prepareStatement(
                            "DELETE FROM sm_network_reservations WHERE reservation_id=?")) {
                        cleanup.setString(1,row.getId().toString()); cleanup.executeUpdate();
                    }
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM sbw_network_players WHERE server_id=?")) {
                    cleanup.setString(1,serverId); cleanup.executeUpdate();
                }
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM sbw_network_arenas WHERE server_id=?")) {
                    cleanup.setString(1, serverId); cleanup.executeUpdate();
                }
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM sbw_network_servers WHERE server_id=?")) {
                    cleanup.setString(1, serverId); cleanup.executeUpdate();
                }
            }
        }
    }
}
