package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Immutable arena advertisement. No world, Bukkit Location or mutable arena reference escapes. */
public final class MinigameArenaSnapshot {
    private final String minigameId, arenaId, displayName, mode, group, serverId;
    private final int minPlayers, maxPlayers, players;
    private final boolean enabled, joinable;
    private final UUID matchId;

    public MinigameArenaSnapshot(String minigameId, String arenaId, String displayName,
                                 String mode, String group, String serverId,
                                 int minPlayers, int maxPlayers, int players,
                                 boolean enabled, boolean joinable, UUID matchId) {
        this.minigameId = Ids.stable(minigameId, "minigame id");
        this.arenaId = Ids.stable(arenaId, "arena id");
        this.displayName = Ids.required(displayName, "display name");
        this.mode = Ids.stable(mode, "mode");
        this.group = Ids.stable(group, "group");
        this.serverId = Ids.stable(serverId, "server id");
        if (minPlayers < 1 || maxPlayers < minPlayers || players < 0 || players > maxPlayers)
            throw new IllegalArgumentException("invalid arena capacity");
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.players = players;
        this.enabled = enabled;
        this.joinable = joinable;
        this.matchId = matchId;
    }

    public String getMinigameId() { return minigameId; }
    public String getArenaId() { return arenaId; }
    public String getDisplayName() { return displayName; }
    public String getMode() { return mode; }
    public String getGroup() { return group; }
    public String getServerId() { return serverId; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getPlayers() { return players; }
    public boolean isEnabled() { return enabled; }
    public boolean isJoinable() { return joinable; }
    public UUID getMatchId() { return matchId; }
}
