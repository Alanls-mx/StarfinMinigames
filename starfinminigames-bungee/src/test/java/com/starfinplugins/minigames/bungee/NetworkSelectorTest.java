package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.NetworkServerSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkSelectorTest {
    private static final long NOW = 100000L;
    private final UUID instance = UUID.randomUUID();

    @Test public void selectsOccupiedWaitingArenaBeforeEmptyCompatibleArena() {
        NetworkArenaSnapshot empty = arena("a", "solo", "AVAILABLE", 0, 2, true, NOW);
        NetworkArenaSnapshot waiting = arena("b", "solo", "WAITING", 1, 2, true, NOW);
        assertEquals("b", NetworkSelector.choose(directory(true, NOW, empty, waiting), allowed(),
            "bedwars", "solo", true, 30000).getArenaId());
        assertEquals("a", NetworkSelector.choose(directory(true, NOW, empty, waiting), allowed(),
            "bedwars", "solo", false, 30000).getArenaId());
    }

    @Test public void selectsOpeningArenaForDirectSpawnReplacement() {
        NetworkArenaSnapshot opening = arena("island", "solo", "OPENING", 1, 2, true, NOW);
        assertEquals("island", NetworkSelector.choose(directory(true, NOW, opening), allowed(),
            "bedwars", "solo", true, 30000).getArenaId());
        NetworkArenaSnapshot closed = arena("island", "solo", "OPENING", 2, 2, true, NOW);
        assertNull(NetworkSelector.choose(directory(true, NOW, closed), allowed(),
            "bedwars", "solo", true, 30000));
    }

    @Test public void filtersExpiredOfflineUnknownOrWrongInstance() {
        NetworkArenaSnapshot arena = arena("a", "solo", "AVAILABLE", 0, 2, true, NOW);
        assertNull(NetworkSelector.choose(directory(true, NOW - 30001, arena), allowed(),
            "bedwars", "solo", true, 30000));
        assertNull(NetworkSelector.choose(directory(false, NOW, arena), allowed(),
            "bedwars", "solo", true, 30000));
        assertNull(NetworkSelector.choose(directory(true, NOW, arena), Collections.emptyMap(),
            "bedwars", "solo", true, 30000));
        NetworkDirectorySnapshot wrongInstance = new NetworkDirectorySnapshot(
            Collections.singletonList(new NetworkServerSnapshot("bedwars-01", UUID.randomUUID(),
                true, 0, 20, NOW)), Collections.singletonList(arena), NOW);
        assertNull(NetworkSelector.choose(wrongInstance, allowed(), "bedwars", "solo", true, 30000));
    }

    @Test public void filtersWrongModeFullClosedAndPreparingArenas() {
        NetworkArenaSnapshot duo = arena("duo", "duo", "AVAILABLE", 0, 2, true, NOW);
        NetworkArenaSnapshot full = arena("full", "solo", "WAITING", 2, 2, true, NOW);
        NetworkArenaSnapshot closed = arena("closed", "solo", "IN_GAME", 0, 2, false, NOW);
        NetworkArenaSnapshot preparing = arena("preparing", "solo", "PREPARING", 0, 2, false, NOW);
        assertTrue(NetworkSelector.options(directory(true, NOW, duo, full, closed, preparing),
            allowed(), "bedwars", "solo", true, 30000).isEmpty());
        assertEquals("duo", NetworkSelector.choose(directory(true, NOW, duo), allowed(),
            "bedwars", "duo", true, 30000).getArenaId());
    }

    private NetworkDirectorySnapshot directory(boolean online, long heartbeat,
                                               NetworkArenaSnapshot... arenas) {
        return new NetworkDirectorySnapshot(Collections.singletonList(
            new NetworkServerSnapshot("bedwars-01", instance, online, 0, 20, heartbeat)),
            Arrays.asList(arenas), NOW);
    }

    private NetworkArenaSnapshot arena(String id, String mode, String state,
                                       int players, int capacity, boolean joinable, long updated) {
        return new NetworkArenaSnapshot("bedwars", "bedwars-01", instance,
            id, mode, "default", state, null, players, capacity,
            true, false, joinable, updated);
    }

    private Map<String, String> allowed() {
        Map<String, String> names = new HashMap<String, String>();
        names.put("bedwars-01", "survival");
        return names;
    }
}
