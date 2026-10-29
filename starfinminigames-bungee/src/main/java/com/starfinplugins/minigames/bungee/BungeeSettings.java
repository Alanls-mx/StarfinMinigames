package com.starfinplugins.minigames.bungee;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

/** Validated proxy identity and explicit catalog-to-Bungee server allowlist. */
final class BungeeSettings {
    final boolean networkEnabled, mysqlEnabled, matchmakingEnabled, preferExisting;
    final boolean partyEnabled, allowSoloParty;
    final String partialTransferPolicy;
    final String networkId, lobbyServer, mysqlHost, mysqlDatabase, mysqlUser, mysqlPassword, sslMode, prefix;
    final int serverTimeoutSeconds, reservationTimeoutSeconds, partyArrivalGraceSeconds, mysqlPort;
    final Map<String, String> serverNames;
    final Map<String, String> messages;
    final RankedMatchmakingSettings ranked;

    BungeeSettings(Configuration yaml, Predicate<String> registeredServer) {
        ranked = new RankedMatchmakingSettings(yaml);
        networkEnabled = yaml.getBoolean("network.enabled", false);
        mysqlEnabled = yaml.getBoolean("mysql.enabled", false);
        matchmakingEnabled = yaml.getBoolean("matchmaking.enabled", true);
        preferExisting = yaml.getBoolean("matchmaking.prefer-existing-matches", true);
        partyEnabled = yaml.getBoolean("party-matchmaking.enabled", true);
        allowSoloParty = yaml.getBoolean("party-matchmaking.allow-solo-party", true);
        partialTransferPolicy = yaml.getString("party-matchmaking.partial-transfer-policy",
            "CANCEL_PENDING").trim().toUpperCase(java.util.Locale.ROOT);
        networkId = yaml.getString("network.network-id", "starfin-network").trim();
        lobbyServer = yaml.getString("network.lobby-server", "auth").trim();
        serverTimeoutSeconds = yaml.getInt("network.server-timeout-seconds", 30);
        reservationTimeoutSeconds = yaml.getInt("matchmaking.reservation-timeout-seconds", 15);
        partyArrivalGraceSeconds = yaml.getInt("party-matchmaking.arrival-grace-seconds", 20);
        mysqlHost = yaml.getString("mysql.host", "localhost").trim();
        mysqlPort = yaml.getInt("mysql.port", 3306);
        mysqlDatabase = yaml.getString("mysql.database", "starfin").trim();
        mysqlUser = yaml.getString("mysql.username", "root").trim();
        mysqlPassword = yaml.getString("mysql.password", "");
        sslMode = yaml.getString("mysql.ssl-mode", "PREFERRED").trim().toUpperCase(java.util.Locale.ROOT);
        prefix = net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&',
            yaml.getString("messages.prefix", "&b&lStarfin &8» "));
        Map<String, String> names = new LinkedHashMap<String, String>();
        Configuration servers = yaml.getSection("network.servers");
        if (servers != null) for (String id : servers.getKeys()) {
            String proxyName = servers.getString(id, "").trim();
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || proxyName.isEmpty()
                    || names.containsValue(proxyName))
                throw new IllegalArgumentException("network.servers possui identidade duplicada ou invalida");
            names.put(id, proxyName);
        }
        serverNames = Collections.unmodifiableMap(names);
        Map<String, String> configuredMessages = new LinkedHashMap<String, String>();
        Configuration messageSection = yaml.getSection("messages");
        if (messageSection != null) for (String key : messageSection.getKeys())
            configuredMessages.put(key, messageSection.getString(key, ""));
        messages = Collections.unmodifiableMap(configuredMessages);
        if (!networkId.matches("[A-Za-z0-9_-]{1,64}") || serverTimeoutSeconds < 5
                || serverTimeoutSeconds > 300 || partyArrivalGraceSeconds < 5
                || partyArrivalGraceSeconds > 120 || reservationTimeoutSeconds < 5
                || reservationTimeoutSeconds > 120 || mysqlPort < 1 || mysqlPort > 65535
                || !("CANCEL_PENDING".equals(partialTransferPolicy)
                    || "CONTINUE".equals(partialTransferPolicy))
                || !mysqlHost.matches("[A-Za-z0-9._:-]+")
                || !mysqlDatabase.matches("[A-Za-z0-9_]+") || mysqlUser.isEmpty()
                || !("DISABLED".equals(sslMode) || "PREFERRED".equals(sslMode)
                    || "REQUIRED".equals(sslMode) || "VERIFY_CA".equals(sslMode)
                    || "VERIFY_IDENTITY".equals(sslMode)))
            throw new IllegalArgumentException("config.yml possui valores de rede ou MySQL invalidos");
        if (networkEnabled) {
            if (!mysqlEnabled || names.isEmpty())
                throw new IllegalArgumentException("network.enabled exige mysql.enabled e network.servers");
            if (!registeredServer.test(lobbyServer))
                throw new IllegalArgumentException("Lobby nao configurado no BungeeCord: " + lobbyServer);
            for (String name : names.values())
                if (!registeredServer.test(name))
                    throw new IllegalArgumentException("Servidor nao autorizado no BungeeCord: " + name);
        }
    }

    String message(String key, String fallback) {
        String value = messages.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    static BungeeSettings load(File folder, ProxyServer proxy) throws IOException {
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Nao foi possivel criar pasta de configuracao");
        File file = new File(folder, "config.yml");
        if (!file.exists()) {
            try (InputStream in = BungeeSettings.class.getClassLoader().getResourceAsStream("config.yml");
                 FileOutputStream out = new FileOutputStream(file)) {
                if (in == null) throw new IOException("config.yml padrao ausente do JAR");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            }
        }
        return new BungeeSettings(ConfigurationProvider.getProvider(YamlConfiguration.class).load(file),
            name -> proxy.getServerInfo(name) != null);
    }
}
