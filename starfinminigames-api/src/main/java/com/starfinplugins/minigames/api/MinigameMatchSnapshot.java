package com.starfinplugins.minigames.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Immutable match view with UUID-based participants. */
public final class MinigameMatchSnapshot {
    private final UUID id;
    private final String minigameId, arenaId, serverId;
    private final MinigameMatchState state;
    private final int maxPlayers, connectedPlayers;
    private final Set<UUID> participantIds;
    private final long startedAtMillis;

    public MinigameMatchSnapshot(UUID id, String minigameId, String arenaId,
                                 String serverId, MinigameMatchState state,
                                 int maxPlayers, int connectedPlayers,
                                 Set<UUID> participantIds, long startedAtMillis) {
        if (id == null || state == null || participantIds == null)
            throw new IllegalArgumentException("match id, state and participants are required");
        this.id = id;
        this.minigameId = Ids.stable(minigameId, "minigame id");
        this.arenaId = Ids.stable(arenaId, "arena id");
        this.serverId = Ids.stable(serverId, "server id");
        LinkedHashSet<UUID> copy = new LinkedHashSet<UUID>(participantIds);
        if (copy.contains(null) || maxPlayers < 1 || copy.size() > maxPlayers
                || connectedPlayers < 0 || connectedPlayers > copy.size() || startedAtMillis < 0)
            throw new IllegalArgumentException("invalid match capacity or participants");
        this.state = state;
        this.maxPlayers = maxPlayers;
        this.connectedPlayers = connectedPlayers;
        this.participantIds = Collections.unmodifiableSet(copy);
        this.startedAtMillis = startedAtMillis;
    }

    public UUID getId() { return id; }
    public String getMinigameId() { return minigameId; }
    public String getArenaId() { return arenaId; }
    public String getServerId() { return serverId; }
    public MinigameMatchState getState() { return state; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getPlayerCount() { return participantIds.size(); }
    public int getConnectedPlayers() { return connectedPlayers; }
    public Set<UUID> getParticipantIds() { return participantIds; }
    public long getStartedAtMillis() { return startedAtMillis; }
}
