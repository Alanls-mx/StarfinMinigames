package com.starfinplugins.minigames.api;

import java.util.UUID;

/** Informational event; no live match or mutable Bukkit object is exposed. */
public final class MinigameEvent {
    public enum Type {
        MINIGAME_REGISTERED, MINIGAME_UNREGISTERED,
        MATCH_CREATED, MATCH_STARTING, MATCH_STARTED,
        PLAYER_JOINED_MATCH, PLAYER_LEFT_MATCH, PLAYER_ELIMINATED,
        MATCH_ENDED, MATCH_REMOVED
    }

    private final Type type;
    private final String minigameId;
    private final MinigameMatchSnapshot match;
    private final UUID playerId;
    private final long occurredAtMillis;

    public MinigameEvent(Type type, String minigameId,
                         MinigameMatchSnapshot match, UUID playerId, long occurredAtMillis) {
        if (type == null || occurredAtMillis < 0) throw new IllegalArgumentException("invalid event");
        this.type = type;
        this.minigameId = Ids.stable(minigameId, "minigame id");
        if (match != null && !minigameId.equals(match.getMinigameId()))
            throw new IllegalArgumentException("match belongs to a different minigame");
        this.match = match;
        this.playerId = playerId;
        this.occurredAtMillis = occurredAtMillis;
    }

    public Type getType() { return type; }
    public String getMinigameId() { return minigameId; }
    public MinigameMatchSnapshot getMatch() { return match; }
    public UUID getPlayerId() { return playerId; }
    public long getOccurredAtMillis() { return occurredAtMillis; }
}
