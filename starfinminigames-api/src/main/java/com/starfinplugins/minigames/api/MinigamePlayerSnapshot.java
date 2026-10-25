package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Player identity and current role; display names are deliberately not keys. */
public final class MinigamePlayerSnapshot {
    public enum Role { PLAYER, RESPAWNING, ELIMINATED, SPECTATOR, DISCONNECTED }

    private final UUID playerId, matchId;
    private final String minigameId, arenaId, serverId;
    private final Role role;

    public MinigamePlayerSnapshot(UUID playerId, String minigameId, UUID matchId,
                                  String arenaId, String serverId, Role role) {
        if (playerId == null || matchId == null || role == null)
            throw new IllegalArgumentException("player id, match id and role are required");
        this.playerId = playerId;
        this.minigameId = Ids.stable(minigameId, "minigame id");
        this.matchId = matchId;
        this.arenaId = Ids.stable(arenaId, "arena id");
        this.serverId = Ids.stable(serverId, "server id");
        this.role = role;
    }

    public UUID getPlayerId() { return playerId; }
    public String getMinigameId() { return minigameId; }
    public UUID getMatchId() { return matchId; }
    public String getArenaId() { return arenaId; }
    public String getServerId() { return serverId; }
    public Role getRole() { return role; }
}
