package com.starfinplugins.minigames.bungee;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.Assume;

/** Explicit test-only connection, verified before any fixture or cleanup SQL. */
final class IntegrationMysql {
    final String host, schema, user, password, url;
    final int port;

    private IntegrationMysql(String host, int port, String schema, String user, String password)
            throws Exception {
        this.host = host; this.port = port; this.schema = schema;
        this.user = user; this.password = password;
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + schema
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement query = c.createStatement();
             ResultSet row = query.executeQuery("SELECT DATABASE()")) {
            if (!row.next() || !schema.equals(row.getString(1)))
                throw new IllegalStateException("MySQL test schema mismatch");
            try (ResultSet marker = query.executeQuery(
                    "SELECT marker FROM starfin_disposable_test_schema LIMIT 1")) {
                if (!marker.next() || !"phase72-disposable-v1".equals(marker.getString(1)))
                    throw new IllegalStateException("Missing disposable MySQL marker");
            }
        }
    }

    static IntegrationMysql load(String schemaOverride, String expectedPrefix) throws Exception {
        boolean required = Boolean.getBoolean("requireIntegrationTests");
        if (!required) Assume.assumeTrue("Set SM_INTEGRATION_TESTS=1 for disposable MySQL tests",
            "1".equals(System.getenv("SM_INTEGRATION_TESTS")));
        String host = System.getenv("SM_TEST_MYSQL_HOST");
        String port = System.getenv("SM_TEST_MYSQL_PORT");
        String schema = schemaOverride == null ? System.getenv("SM_TEST_MYSQL_DATABASE")
            : System.getenv(schemaOverride);
        String user = System.getenv("SM_TEST_MYSQL_USER");
        String password = System.getenv("SM_TEST_MYSQL_PASSWORD");
        if (host == null || port == null || schema == null || user == null || password == null)
            throw new IllegalStateException("Missing disposable MySQL test configuration");
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)))
            throw new IllegalStateException("JDBC integration tests require loopback MySQL");
        if (!schema.matches(expectedPrefix + "[a-z0-9_]+")
                || schema.matches("(?i).*(production|prod|minecraft|starfin).*"))
            throw new IllegalStateException("Unsafe disposable MySQL schema");
        int parsedPort = Integer.parseInt(port);
        if (parsedPort < 1 || parsedPort > 65535 || user.isEmpty())
            throw new IllegalStateException("Invalid MySQL test connection");
        return new IntegrationMysql(host, parsedPort, schema, user, password);
    }
}
