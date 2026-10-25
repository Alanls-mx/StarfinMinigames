package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.NetworkArenaSnapshot;
import com.starfinplugins.minigames.api.NetworkDirectorySnapshot;
import com.starfinplugins.minigames.api.NetworkServerSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure selection policy; database transactions still recheck the chosen row. */
public final class NetworkSelector {
    private NetworkSelector() { }

    public static NetworkArenaSnapshot choose(NetworkDirectorySnapshot directory,
                                              Map<String, String> authorizedServers,
                                              String minigameId, String mode,
                                              boolean preferExisting, long timeoutMillis) {
        List<NetworkArenaSnapshot> options = options(directory, authorizedServers, minigameId,
            mode, preferExisting, timeoutMillis);
        return options.isEmpty() ? null : options.get(0);
    }

    public static List<NetworkArenaSnapshot> options(NetworkDirectorySnapshot directory,
                                                     Map<String, String> authorizedServers,
                                                     String minigameId, String mode,
                                                     boolean preferExisting, long timeoutMillis) {
        Map<String, NetworkServerSnapshot> servers = new HashMap<String, NetworkServerSnapshot>();
        for (NetworkServerSnapshot server : directory.getServers()) servers.put(server.getServerId(), server);
        List<NetworkArenaSnapshot> options = new ArrayList<NetworkArenaSnapshot>();
        long now = directory.getSampledAtMillis();
        for (NetworkArenaSnapshot arena : directory.getArenas()) {
            NetworkServerSnapshot server = servers.get(arena.getServerId());
            if (!authorizedServers.containsKey(arena.getServerId()) || server == null
                    || !server.getInstanceId().equals(arena.getInstanceId())
                    || !server.isAvailable(now, timeoutMillis) || !arena.isAvailable()
                    || arena.getUpdatedAtMillis() > now
                    || now - arena.getUpdatedAtMillis() > timeoutMillis
                    || !minigameId.equalsIgnoreCase(arena.getMinigameId())
                    || (mode != null && !mode.equalsIgnoreCase(arena.getMode()))) continue;
            options.add(arena);
        }
        options.sort(Comparator.comparingInt((NetworkArenaSnapshot arena) ->
            preferExisting && arena.getPlayers() > 0 ? 0 : 1)
            .thenComparingInt(arena -> preferExisting ? -arena.getPlayers() : 0)
            .thenComparing(NetworkArenaSnapshot::getServerId)
            .thenComparing(NetworkArenaSnapshot::getArenaId));
        return options;
    }
}
