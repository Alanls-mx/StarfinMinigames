package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Short-lived transfer intent. Confirmation requires a real match admission. */
public final class ReservationSnapshot {
    private final UUID id, playerId, serverInstance;
    private final String minigameId, mode, serverId, arenaId;
    private final ReservationState state;
    private final long createdAtMillis, expiresAtMillis;
    private final UUID groupId, partyId, leaderId;
    private final long partyRevision;
    private final int groupSize;
    private final String teamId;
    private final UUID rankedAllocationId;
    private final String competitiveContext;

    public ReservationSnapshot(UUID id, UUID playerId, String minigameId, String mode,
                               String serverId, UUID serverInstance, String arenaId,
                               ReservationState state, long createdAtMillis, long expiresAtMillis) {
        this(id, playerId, minigameId, mode, serverId, serverInstance, arenaId,
            state, createdAtMillis, expiresAtMillis, null, null, null, 0L, 1, null);
    }

    public ReservationSnapshot(UUID id, UUID playerId, String minigameId, String mode,
                               String serverId, UUID serverInstance, String arenaId,
                               ReservationState state, long createdAtMillis, long expiresAtMillis,
                               UUID groupId, UUID partyId, UUID leaderId, long partyRevision,
                               int groupSize, String teamId) {
        this(id, playerId, minigameId, mode, serverId, serverInstance, arenaId, state,
            createdAtMillis, expiresAtMillis, groupId, partyId, leaderId, partyRevision,
            groupSize, teamId, null, null);
    }

    public ReservationSnapshot(UUID id, UUID playerId, String minigameId, String mode,
                               String serverId, UUID serverInstance, String arenaId,
                               ReservationState state, long createdAtMillis, long expiresAtMillis,
                               UUID groupId, UUID partyId, UUID leaderId, long partyRevision,
                               int groupSize, String teamId, UUID rankedAllocationId,
                               String competitiveContext) {
        if (id == null || playerId == null || serverInstance == null || state == null
                || createdAtMillis < 0 || expiresAtMillis <= createdAtMillis
                || groupSize < 1 || groupSize > 100 || (groupId == null && teamId != null)
                || (rankedAllocationId == null) != (competitiveContext == null)
                || (competitiveContext != null && !competitiveContext.matches("[a-z0-9_-]{1,32}")))
            throw new IllegalArgumentException("invalid reservation");
        this.groupId = groupId;
        this.partyId = partyId;
        this.leaderId = leaderId;
        this.partyRevision = partyRevision;
        this.groupSize = groupSize;
        this.teamId = teamId;
        this.rankedAllocationId = rankedAllocationId;
        this.competitiveContext = competitiveContext;
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
    public UUID getGroupId() { return groupId; }
    public UUID getPartyId() { return partyId; }
    public UUID getLeaderId() { return leaderId; }
    public long getPartyRevision() { return partyRevision; }
    public int getGroupSize() { return groupSize; }
    public String getTeamId() { return teamId; }
    public boolean isRanked() { return rankedAllocationId != null; }
    public UUID getRankedAllocationId() { return rankedAllocationId; }
    public String getCompetitiveContext() { return competitiveContext; }
}
