package com.starfinplugins.minigames.bungee;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.starfinplugins.minigames.api.ReservationSnapshot;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.scheduler.ScheduledTask;

/** Bungee entry point; JDBC is confined to a bounded worker pool. */
public final class StarfinMinigamesBungee extends Plugin implements Listener {
    private static final String SAME_SERVER_CHANNEL = "SMJoin";
    private volatile BungeeSettings settings;
    private volatile BungeeDatabase database;
    private ThreadPoolExecutor io;
    private ScheduledTask cleanup;
    private ScheduledTask rankedTick;
    private final UUID proxyId = UUID.randomUUID();
    private RankedQueueService rankedQueue;
    private final Map<UUID, ReservationSnapshot> transfers = new ConcurrentHashMap<UUID, ReservationSnapshot>();
    private final java.util.Set<UUID> inFlight = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private volatile boolean stopping;

    @Override public void onEnable() {
        try {
            settings = BungeeSettings.load(getDataFolder(), getProxy());
            io = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(64), runnable -> {
                    Thread thread = new Thread(runnable, "StarfinMinigames-Bungee-IO");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
            getProxy().getPluginManager().registerCommand(this, new NetworkCommand(this));
            getProxy().getPluginManager().registerCommand(this, new JoinCommand(this));
            rankedQueue = new RankedQueueService(this, proxyId);
            getProxy().getPluginManager().registerCommand(this, new RankedCommand(rankedQueue));
            getProxy().getPluginManager().registerListener(this, this);
            getProxy().registerChannel(SAME_SERVER_CHANNEL);
            if (settings.networkEnabled) {
                submitIo(() -> {
                    try {
                        database = new BungeeDatabase(settings);
                        getLogger().info("Catalogo MySQL pronto; servidores autorizados: "
                            + settings.serverNames.keySet());
                    } catch (Exception error) {
                        getLogger().severe("Catalogo indisponivel (" + error.getClass().getSimpleName()
                            + "); confira MySQL e sbw_network_* sem expor credenciais.");
                    }
                });
                cleanup = getProxy().getScheduler().schedule(this, () -> submitIo(() -> {
                    BungeeDatabase db = database;
                    if (db == null) return;
                    try { db.expire(); }
                    catch (SQLException error) { getLogger().warning("Nao foi possivel expirar reservas."); }
                    long now = System.currentTimeMillis();
                    transfers.entrySet().removeIf(entry -> entry.getValue().getExpiresAtMillis() + 90000L < now);
                }), 10, 10, TimeUnit.SECONDS);
                rankedTick = getProxy().getScheduler().schedule(this,
                    () -> rankedQueue.tick(), 2, settings.ranked.heartbeatSeconds, TimeUnit.SECONDS);
            } else getLogger().info("Catalogo desativado; comandos administrativos disponiveis.");
        } catch (Exception error) {
            getLogger().severe("StarfinMinigames-Bungee nao iniciou: " + error.getMessage());
            getProxy().getPluginManager().unregisterCommands(this);
            getProxy().getPluginManager().unregisterListeners(this);
            if (io != null) io.shutdownNow();
        }
    }

    @Override public void onDisable() {
        stopping = true;
        if (cleanup != null) cleanup.cancel();
        if (rankedTick != null) rankedTick.cancel();
        getProxy().getPluginManager().unregisterCommands(this);
        getProxy().getPluginManager().unregisterListeners(this);
        getProxy().unregisterChannel(SAME_SERVER_CHANNEL);
        if (io != null) {
            io.shutdown();
            try { if (!io.awaitTermination(5, TimeUnit.SECONDS)) io.shutdownNow(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); io.shutdownNow(); }
        }
        if (database != null) database.close();
        database = null;
        transfers.clear();
        inFlight.clear();
    }

    BungeeSettings settings() { return settings; }
    UUID proxyId() { return proxyId; }
    BungeeDatabase database() { return database; }
    ProxyServer proxy() { return getProxy(); }

    boolean submitIo(Runnable task) {
        if (stopping || io == null || io.isShutdown()) return false;
        try { io.execute(task); return true; }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            getLogger().warning("Fila de operacoes de rede ocupada; requisicao descartada.");
            return false;
        }
    }

    void send(CommandSender target, String message) {
        target.sendMessage(TextComponent.fromLegacyText(settings.prefix
            + net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message)));
    }

    void sendKey(CommandSender target, String key, String fallback, String... replacements) {
        String message = settings.message(key, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2)
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        send(target, message);
    }

    void requestJoin(ProxiedPlayer player, String game, String mode) {
        if (settings.partyEnabled && getProxy().getPluginManager().getPlugin("StarfinSocial") != null) {
            try { PartyJoinBridge.request(this, player, game, mode); }
            catch (LinkageError | RuntimeException error) {
                getLogger().warning("Integracao com StarfinSocial indisponivel ("
                    + error.getClass().getSimpleName() + ").");
                sendKey(player, "party-unavailable", "&cNao foi possivel consultar sua Party.");
            }
            return;
        }
        requestSoloJoin(player, game, mode);
    }

    void requestSoloJoin(ProxiedPlayer player, String game, String mode) {
        BungeeSettings active = settings;
        if (!active.networkEnabled || !active.matchmakingEnabled || database == null) {
            sendKey(player, "matchmaking-unavailable", "&cMatchmaking indisponivel neste momento.");
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) {
            sendKey(player, "search-in-progress", "&eJa existe uma busca em andamento.");
            return;
        }
        if (!submitIo(() -> {
            ReservationSnapshot reservation;
            try { reservation = database.reserve(playerId, game, mode); }
            catch (IllegalStateException activeTransfer) {
                inFlight.remove(playerId);
                sendKey(player, "transfer-pending", "&eJa existe uma transferencia pendente para voce.");
                return;
            } catch (Exception error) {
                inFlight.remove(playerId);
                getLogger().warning("Busca de partida falhou (" + error.getClass().getSimpleName() + ").");
                sendKey(player, "catalog-error", "&cNao foi possivel consultar as partidas agora.");
                return;
            }
            if (reservation == null) {
                inFlight.remove(playerId);
                sendKey(player, "arena-unavailable", "&eNenhuma arena disponivel para {game} {mode}.",
                    "game", game, "mode", mode == null ? "" : mode);
                return;
            }
            String name = active.serverNames.get(reservation.getServerId());
            ServerInfo destination = name == null ? null : getProxy().getServerInfo(name);
            if (destination == null || getProxy().getPlayer(playerId) != player) {
                inFlight.remove(playerId);
                submitIo(() -> fail(reservation, false));
                if (getProxy().getPlayer(playerId) == player)
                    sendKey(player, "destination-unavailable",
                        "&cDestino indisponivel neste momento.");
                return;
            }
            if (player.getServer() != null && destination.equals(player.getServer().getInfo())) {
                inFlight.remove(playerId);
                if (deliverSameServer(player, destination, reservation))
                    sendKey(player, "connecting", "&aEntrando na arena {arena}...",
                        "arena", reservation.getArenaId());
                else {
                    submitIo(() -> fail(reservation, false));
                    sendKey(player, "connection-failed",
                        "&cNao foi possivel entregar a entrada para a arena.");
                }
                return;
            }
            transfers.put(playerId, reservation);
            player.connect(destination, (connected, error) -> {
                inFlight.remove(playerId);
                if (error != null || !Boolean.TRUE.equals(connected)) {
                    transfers.remove(playerId, reservation);
                    submitIo(() -> fail(reservation, false));
                    if (getProxy().getPlayer(playerId) == player)
                        sendKey(player, "connection-failed",
                            "&cNao foi possivel conectar ao servidor da partida.");
                } else sendKey(player, "connecting", "&aConectando a arena {arena}...",
                    "arena", reservation.getArenaId());
            });
        })) {
            inFlight.remove(playerId);
            sendKey(player, "proxy-busy", "&cO proxy esta ocupado. Tente novamente em instantes.");
        }
    }

    boolean deliverSameServer(ProxiedPlayer player, ServerInfo destination,
                              ReservationSnapshot reservation) {
        if (player == null || destination == null || reservation == null
                || player.getServer() == null
                || !destination.equals(player.getServer().getInfo())) return false;
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF(reservation.getPlayerId().toString());
        return destination.sendData(SAME_SERVER_CHANNEL, output.toByteArray(), false);
    }

    void fail(ReservationSnapshot reservation, boolean pendingOnly) {
        BungeeDatabase db = database;
        if (db == null) return;
        try { if (pendingOnly) db.failPendingOnly(reservation); else db.fail(reservation); }
        catch (SQLException error) { getLogger().warning("Nao foi possivel liberar uma reserva; ela expirara."); }
    }

    boolean acquireGroup(List<UUID> members) {
        java.util.List<UUID> acquired = new java.util.ArrayList<UUID>();
        for (UUID id : members) {
            if (!inFlight.add(id)) {
                for (UUID previous : acquired) inFlight.remove(previous);
                return false;
            }
            acquired.add(id);
        }
        return true;
    }

    void releaseGroup(List<UUID> members) {
        for (UUID id : members) inFlight.remove(id);
    }

    void trackTransfer(UUID player, ReservationSnapshot reservation) {
        transfers.put(player, reservation);
    }

    void clearTransfer(UUID player, ReservationSnapshot reservation) {
        if (transfers.remove(player, reservation)) inFlight.remove(player);
    }

    void releaseFlight(UUID player) { inFlight.remove(player); }

    @EventHandler public void onDisconnect(PlayerDisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        inFlight.remove(id);
        ReservationSnapshot reservation = transfers.remove(id);
        if (reservation != null) submitIo(() -> fail(reservation, true));
    }

    String reloadSafeSettings() {
        try {
            BungeeSettings next = BungeeSettings.load(getDataFolder(), getProxy());
            BungeeSettings old = settings;
            if (old.networkEnabled != next.networkEnabled || old.mysqlEnabled != next.mysqlEnabled
                    || !old.networkId.equals(next.networkId) || !old.lobbyServer.equals(next.lobbyServer)
                    || !old.serverNames.equals(next.serverNames) || !old.mysqlHost.equals(next.mysqlHost)
                    || old.ranked.heartbeatSeconds != next.ranked.heartbeatSeconds
                    || old.mysqlPort != next.mysqlPort || !old.mysqlDatabase.equals(next.mysqlDatabase)
                    || !old.mysqlUser.equals(next.mysqlUser) || !old.mysqlPassword.equals(next.mysqlPassword)
                    || !old.sslMode.equals(next.sslMode))
                return "&eIdentidade, servidores ou MySQL mudaram; reinicie o proxy para aplicar.";
            settings = next;
            if (database != null) database.updateSettings(next);
            return "&aMensagens e limites de matchmaking recarregados.";
        } catch (IOException | IllegalArgumentException error) {
            return "&cConfiguração invalida: " + error.getClass().getSimpleName();
        }
    }
}
