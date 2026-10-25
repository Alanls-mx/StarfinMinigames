package com.starfinplugins.minigames.api;

import java.util.Collection;

/** A game-owned, read-only projection. Call on the Spigot primary thread. */
public interface MinigameProvider {
    MinigameDescriptor descriptor();
    Collection<MinigameArenaSnapshot> arenas();
    Collection<MinigameMatchSnapshot> matches();
    Collection<MinigamePlayerSnapshot> players();
}
