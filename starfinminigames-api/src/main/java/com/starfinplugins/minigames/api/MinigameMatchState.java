package com.starfinplugins.minigames.api;

/** Public states shared by different games. Internal state machines remain game-owned. */
public enum MinigameMatchState {
    WAITING, STARTING, IN_GAME, ENDING, RESETTING, CLOSED
}
