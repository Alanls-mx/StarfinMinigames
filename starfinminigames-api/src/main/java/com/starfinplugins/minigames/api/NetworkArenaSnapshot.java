package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Arena advertisement from a specific live server instance. */
public final class NetworkArenaSnapshot {
    private final String minigameId, serverId, arenaId, mode, group, state;
    private final UUID instanceId, matchId;
    private final int players, capacity;
    private final boolean enabled, editing, joinable;
    private final long updatedAtMillis;

    public NetworkArenaSnapshot(String minigameId, String serverId, UUID instanceId,
                                String arenaId, String mode, String group, String state,
                                UUID matchId, int players, int capacity,
                                boolean enabled, boolean editing, boolean joinable, long updatedAtMillis) {
        this.minigameId = Ids.stable(minigameId, "minigame id");
        this.serverId = Ids.stable(serverId, "server id");
        this.arenaId = Ids.stable(arenaId, "arena id");
        this.mode = Ids.stable(mode, "mode");
        this.group = Ids.stable(group, "group");
        this.state = Ids.required(state, "state");
        if (instanceId == null || players < 0 || capacity < 1 || players > capacity || updatedAtMillis < 0)
            throw new IllegalArgumentException("invalid arena advertisement");
        this.instanceId = instanceId;
        this.matchId = matchId;
        this.players = players;
        this.capacity = capacity;
        this.enabled = enabled;
        this.editing = editing;
        this.joinable = joinable;
        this.updatedAtMillis = updatedAtMillis;
    }

    public String getMinigameId() { return minigameId; }
    public String getServerId() { return serverId; }
    public UUID getInstanceId() { return instanceId; }
    public String getArenaId() { return arenaId; }
    public String getMode() { return mode; }
    public String getGroup() { return group; }
    public String getState() { return state; }
    public UUID getMatchId() { return matchId; }
    public int getPlayers() { return players; }
    public int getCapacity() { return capacity; }
    public boolean isEnabled() { return enabled; }
    public boolean isEditing() { return editing; }
    public boolean isJoinable() { return joinable; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public boolean isAvailable() {
        return enabled && !editing && joinable && players < capacity
            && ("AVAILABLE".equalsIgnoreCase(state) || "WAITING".equalsIgnoreCase(state)
                || "STARTING".equalsIgnoreCase(state) || "OPENING".equalsIgnoreCase(state));
    }
}
