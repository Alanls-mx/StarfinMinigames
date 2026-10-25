package com.starfinplugins.minigames.api;

import java.util.List;
import java.util.UUID;

/** Public read-only discovery surface plus explicit owner registration handles. */
public interface MinigameRegistry {
    String API_VERSION = "1.0";

    String getServerId();
    UUID getInstanceId();
    MinigameRegistration register(MinigameProvider provider);
    MinigameSubscription subscribe(MinigameLifecycleListener listener);
    List<MinigameDescriptor> minigames();
    MinigameDescriptor find(String minigameId);
    List<MinigameArenaSnapshot> arenas(String minigameId);
    List<MinigameMatchSnapshot> matches(String minigameId);
    List<MinigamePlayerSnapshot> players(String minigameId);
}
