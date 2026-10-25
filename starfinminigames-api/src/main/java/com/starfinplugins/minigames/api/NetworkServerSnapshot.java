package com.starfinplugins.minigames.api;

import java.util.UUID;

/** A server lease observed from a shared catalog; never an address to trust directly. */
public final class NetworkServerSnapshot {
    private final String serverId;
    private final UUID instanceId;
    private final boolean online;
    private final int players, capacity;
    private final long heartbeatMillis;

    public NetworkServerSnapshot(String serverId, UUID instanceId, boolean online,
                                 int players, int capacity, long heartbeatMillis) {
        this.serverId = Ids.stable(serverId, "server id");
        if (instanceId == null || players < 0 || capacity < 1 || players > capacity || heartbeatMillis < 0)
            throw new IllegalArgumentException("invalid server lease");
        this.instanceId = instanceId;
        this.online = online;
        this.players = players;
        this.capacity = capacity;
        this.heartbeatMillis = heartbeatMillis;
    }

    public String getServerId() { return serverId; }
    public UUID getInstanceId() { return instanceId; }
    public boolean isOnline() { return online; }
    public int getPlayers() { return players; }
    public int getCapacity() { return capacity; }
    public long getHeartbeatMillis() { return heartbeatMillis; }
    public boolean isAvailable(long nowMillis, long timeoutMillis) {
        return online && players < capacity && timeoutMillis > 0
            && heartbeatMillis <= nowMillis && nowMillis - heartbeatMillis <= timeoutMillis;
    }
}
