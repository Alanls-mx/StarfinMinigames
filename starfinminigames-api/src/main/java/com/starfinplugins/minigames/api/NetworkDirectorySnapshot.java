package com.starfinplugins.minigames.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Point-in-time network view. Consumers cannot change source catalog rows. */
public final class NetworkDirectorySnapshot {
    private final List<NetworkServerSnapshot> servers;
    private final List<NetworkArenaSnapshot> arenas;
    private final long sampledAtMillis;

    public NetworkDirectorySnapshot(List<NetworkServerSnapshot> servers,
                                    List<NetworkArenaSnapshot> arenas, long sampledAtMillis) {
        if (servers == null || arenas == null || servers.contains(null) || arenas.contains(null)
                || sampledAtMillis < 0) throw new IllegalArgumentException("invalid directory");
        this.servers = Collections.unmodifiableList(new ArrayList<NetworkServerSnapshot>(servers));
        this.arenas = Collections.unmodifiableList(new ArrayList<NetworkArenaSnapshot>(arenas));
        this.sampledAtMillis = sampledAtMillis;
    }

    public List<NetworkServerSnapshot> getServers() { return servers; }
    public List<NetworkArenaSnapshot> getArenas() { return arenas; }
    public long getSampledAtMillis() { return sampledAtMillis; }
}
