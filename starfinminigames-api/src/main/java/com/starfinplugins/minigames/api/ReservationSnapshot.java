package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Short-lived transfer intent. Confirmation requires a real match admission. */
public final class ReservationSnapshot {
    private final UUID id, playerId, serverInstance;
    private final String minigameId, mode, serverId, arenaId;
    private final ReservationState state;
    private final long createdAtMillis, expiresAtMillis;

    public ReservationSnapshot(UUID id, UUID playerId, String minigameId, String mode,
                               String serverId, UUID serverInstance, String arenaId,
                               ReservationState state, long createdAtMillis, long expiresAtMillis) {
        if (id == null || playerId == null || serverInstance == null || state == null
                || createdAtMillis < 0 || expiresAtMillis <= createdAtMillis)
            throw new IllegalArgumentException("invalid reservation");
        this.id = id;
        this.playerId = playerId;
        this.minigameId = Ids.stable(minigameId, "minigame id");
        this.mode = Ids.stable(mode, "mode");
        this.serverId = Ids.stable(serverId, "server id");
        this.serverInstance = serverInstance;
        this.arenaId = Ids.stable(arenaId, "arena id");
        this.state = state;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
    }

    public UUID getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public String getMinigameId() { return minigameId; }
    public String getMode() { return mode; }
    public String getServerId() { return serverId; }
    public UUID getServerInstance() { return serverInstance; }
    public String getArenaId() { return arenaId; }
    public ReservationState getState() { return state; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getExpiresAtMillis() { return expiresAtMillis; }
}
