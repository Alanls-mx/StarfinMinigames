package com.starfinplugins.minigames.api;

/** Delivered synchronously on the thread that publishes the event (main thread on Spigot). */
public interface MinigameLifecycleListener {
    void onMinigameEvent(MinigameEvent event);
}
