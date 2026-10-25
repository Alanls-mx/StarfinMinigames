package com.starfinplugins.minigames.api;

/** Owner handle. Close it at plugin shutdown; Spigot also closes it on PluginDisableEvent. */
public interface MinigameRegistration extends AutoCloseable {
    String getMinigameId();
    void publish(MinigameEvent event);
    @Override void close();
}
