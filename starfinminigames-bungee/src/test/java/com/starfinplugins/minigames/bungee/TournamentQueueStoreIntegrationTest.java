package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.ReservationJdbc;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.minigames.api.ReservationState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.config.Configuration;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Disposable-schema proof of all-or-nothing tournament allocation. */
public class TournamentQueueStoreIntegrationTest {
    @Test public void allocatesFullRosterOnlyAndExcludesCasual() throws Exception {
        String port = System.getenv("SM_TEST_MYSQL_PORT");
        Assume.assumeTrue(System.getenv("SM_TEST_MYSQL_HOST") != null && port != null);
        String host = System.getenv("SM_TEST_MYSQL_HOST");
        String schema = System.getenv("SM_TEST_MYSQL_DATABASE");
        String user = System.getenv("SM_TEST_MYSQL_USER");
        String password = System.getenv("SM_TEST_MYSQL_PASSWORD");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + schema
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        String server = "tour-" + UUID.randomUUID().toString().substring(0, 8);
        String tournament = "q" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID instance = UUID.randomUUID(), firstTeam = UUID.randomUUID(),
            secondTeam = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        UUID match = UUID.randomUUID(), game = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement sql = c.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_servers ("
                + "server_id VARCHAR(32) PRIMARY KEY,instance_id CHAR(36),online TINYINT,"
                + "online_players INT,max_players INT,last_heartbeat BIGINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_arenas ("
                + "server_id VARCHAR(32),arena_id VARCHAR(32),configuration_yaml MEDIUMTEXT,"
                + "mode VARCHAR(16),arena_group VARCHAR(32),state VARCHAR(16),"
                + "match_id CHAR(36) NULL,players INT,max_players INT,enabled TINYINT,"
                + "editing TINYINT,joinable TINYINT,updated_at BIGINT,deleted_at BIGINT,"
                + "PRIMARY KEY(server_id,arena_id))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_network_players ("
                + "player_uuid CHAR(36) PRIMARY KEY,server_id VARCHAR(32),"
                + "server_instance CHAR(36),arena_id VARCHAR(32),team_id VARCHAR(32),online TINYINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournaments ("
                + "tournament_id VARCHAR(32) PRIMARY KEY,mode VARCHAR(16),arena_group VARCHAR(32),"
                + "team_size INT,state VARCHAR(24))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournament_matches ("
                + "match_id CHAR(36) PRIMARY KEY,tournament_id VARCHAR(32),team_a CHAR(36),"
                + "team_b CHAR(36),state VARCHAR(24))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournament_members ("
                + "tournament_id VARCHAR(32),player_uuid CHAR(36),team_id CHAR(36),"
                + "confirmed TINYINT,PRIMARY KEY(tournament_id,player_uuid))");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournament_games ("
                + "game_id CHAR(36) PRIMARY KEY,match_id CHAR(36),tournament_id VARCHAR(32),"
                + "state VARCHAR(24),created_at BIGINT,allocation_id CHAR(36) NULL,"
                + "server_id VARCHAR(32) NULL,server_instance CHAR(36) NULL,arena_id VARCHAR(32) NULL,"
                + "arena_team_a VARCHAR(32) NULL,arena_team_b VARCHAR(32) NULL,"
                + "lease_expires BIGINT DEFAULT 0,updated_at BIGINT DEFAULT 0)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournament_checkin_policy ("
                + "tournament_id VARCHAR(32) PRIMARY KEY,required TINYINT,"
                + "grace_minutes INT,automatic_walkover TINYINT)");
            sql.execute("CREATE TABLE IF NOT EXISTS sbw_tournament_checkins ("
                + "match_id CHAR(36),player_uuid CHAR(36),confirmed_at BIGINT,"
                + "PRIMARY KEY(match_id,player_uuid))");
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_network_servers VALUES(?,?,?,?,?,?)")) {
                insert.setString(1, server); insert.setString(2, instance.toString());
                insert.setBoolean(3, true); insert.setInt(4, 0);
                insert.setInt(5, 10); insert.setLong(6, now); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_network_arenas VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, server); insert.setString(2, "cup");
                insert.setString(3, "teams:\n  red:\n    capacity: 1\n  blue:\n    capacity: 1\n");
                insert.setString(4, "solo"); insert.setString(5, "tournament");
                insert.setString(6, "AVAILABLE"); insert.setString(7, null);
                insert.setInt(8, 0); insert.setInt(9, 2); insert.setBoolean(10, true);
                insert.setBoolean(11, false); insert.setBoolean(12, true);
                insert.setLong(13, now); insert.setLong(14, 0); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournaments(tournament_id,mode,arena_group,team_size,state)"
                    + " VALUES(?,?,?,?,'RUNNING')")) {
                insert.setString(1, tournament); insert.setString(2, "solo");
                insert.setString(3, "tournament"); insert.setInt(4, 1); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournament_matches(match_id,tournament_id,team_a,team_b,state)"
                    + " VALUES(?,?,?,?,'IN_PROGRESS')")) {
                insert.setString(1, match.toString()); insert.setString(2, tournament);
                insert.setString(3, firstTeam.toString()); insert.setString(4, secondTeam.toString());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournament_games(game_id,match_id,tournament_id,state,created_at)"
                    + " VALUES(?,?,?,'REQUESTED',?)")) {
                insert.setString(1, game.toString()); insert.setString(2, match.toString());
                insert.setString(3, tournament); insert.setLong(4, now); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournament_checkin_policy VALUES(?,1,10,0)")) {
                insert.setString(1, tournament); insert.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournament_members(tournament_id,player_uuid,team_id,confirmed)"
                    + " VALUES(?,?,?,1)")) {
                for (UUID[] row : new UUID[][] {{first, firstTeam}, {second, secondTeam}}) {
                    insert.setString(1, tournament); insert.setString(2, row[0].toString());
                    insert.setString(3, row[1].toString()); insert.executeUpdate();
                }
            }
        }
        Configuration yaml = new Configuration();
        yaml.set("network.enabled", true); yaml.set("network.servers." + server, "survival");
        yaml.set("mysql.enabled", true); yaml.set("mysql.host", host);
        yaml.set("mysql.port", Integer.parseInt(port)); yaml.set("mysql.database", schema);
        yaml.set("mysql.username", user); yaml.set("mysql.password", password);
        yaml.set("mysql.ssl-mode", "DISABLED"); yaml.set("tournaments.enabled", true);
        BungeeSettings settings = new BungeeSettings(yaml, name -> true);
        try (BungeeDatabase db = new BungeeDatabase(settings)) {
            assertNull(db.reserve(UUID.randomUUID(), "bedwars", "solo"));
            TournamentQueueStore store = new TournamentQueueStore(db, settings);
            TournamentQueueStore.Request request = store.requested(16).stream()
                .filter(row -> row.tournamentId.equals(tournament)).findFirst().get();
            Map<UUID, UUID> roster = store.roster(request);
            assertEquals(2, roster.size());
            NetworkDirectorySnapshot directory = db.directory();
            NetworkArenaSnapshot arena = directory.getArenas().stream()
                .filter(row -> row.getServerId().equals(server)).findFirst().get();
            assertNull(store.allocate(request, arena, roster));
            try (Connection c = db.connection(); PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO sbw_tournament_checkins VALUES(?,?,?)")) {
                for (UUID member : new UUID[]{first, second}) {
                    insert.setString(1, match.toString());
                    insert.setString(2, member.toString());
                    insert.setLong(3, System.currentTimeMillis()); insert.executeUpdate();
                }
            }
            TournamentQueueStore.Allocation allocation = store.allocate(request, arena, roster);
            assertNotNull(allocation); assertEquals(2, allocation.reservations.size());
            assertNull(store.allocate(request, arena, roster));
            for (ReservationSnapshot row : allocation.reservations) {
                assertTrue(row.isTournament()); assertEquals(game, row.getTournamentGameId());
                try (Connection c = db.connection()) {
                    ReservationSnapshot claimed = ReservationJdbc.claim(c, row.getPlayerId(),
                        server, instance, System.currentTimeMillis(), 60000);
                    assertNotNull(claimed); assertTrue(claimed.isTournament());
                    assertEquals(allocation.allocationId, claimed.getTournamentAllocationId());
                }
            }
        }
    }
}
