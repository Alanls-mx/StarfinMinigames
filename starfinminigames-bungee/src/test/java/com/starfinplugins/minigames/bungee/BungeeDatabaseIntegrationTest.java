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
                + "server_id VARCHAR(32),arena_id VARCHAR(32),mode VARCHAR(16),arena_group VARCHAR(32),"
                + "state VARCHAR(16),match_id CHAR(36) NULL,players INT,max_players INT,"
                + "enabled TINYINT,editing TINYINT,joinable TINYINT,updated_at BIGINT,"
                + "deleted_at BIGINT,PRIMARY KEY(server_id,arena_id))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_servers VALUES (?,?,?,?,?,?)")) {
                insert.setString(1, serverId); insert.setString(2, instance.toString());
                insert.setBoolean(3, true); insert.setInt(4, 0); insert.setInt(5, 20);
                insert.setLong(6, now); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, serverId); insert.setString(2, "duel");
                insert.setString(3, "SOLO"); insert.setString(4, "default");
                insert.setString(5, "AVAILABLE"); insert.setString(6, null);
                insert.setInt(7, 0); insert.setInt(8, 1);
                insert.setBoolean(9, true); insert.setBoolean(10, false);
                insert.setBoolean(11, true); insert.setLong(12, now);
                insert.setLong(13, 0); insert.executeUpdate();
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
                    "INSERT INTO sbw_network_arenas VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,serverId); insert.setString(2,"party");
                insert.setString(3,"SOLO"); insert.setString(4,"default");
                insert.setString(5,"AVAILABLE"); insert.setString(6,null);
                insert.setInt(7,0); insert.setInt(8,2);
                insert.setBoolean(9,true); insert.setBoolean(10,false);
                insert.setBoolean(11,true); insert.setLong(12,System.currentTimeMillis());
                insert.setLong(13,0); insert.executeUpdate();
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
