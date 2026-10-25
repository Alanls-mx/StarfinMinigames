package com.starfinplugins.minigames.api;

public interface MinigameSubscription extends AutoCloseable {
    @Override void close();
}
