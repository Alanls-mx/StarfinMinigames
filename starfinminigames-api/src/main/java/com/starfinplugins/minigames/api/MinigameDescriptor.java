package com.starfinplugins.minigames.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Immutable identity and available modes of one registered game. */
public final class MinigameDescriptor {
    private final String id, displayName, serverId;
    private final UUID instanceId;
    private final Set<String> modes;

    public MinigameDescriptor(String id, String displayName, String serverId,
                              UUID instanceId, Set<String> modes) {
        this.id = Ids.stable(id, "minigame id");
        this.displayName = Ids.required(displayName, "display name");
        this.serverId = Ids.stable(serverId, "server id");
        if (instanceId == null || modes == null)
            throw new IllegalArgumentException("instance id and modes are required");
        this.instanceId = instanceId;
        LinkedHashSet<String> copy = new LinkedHashSet<String>();
        for (String mode : modes) copy.add(Ids.stable(mode, "mode"));
        this.modes = Collections.unmodifiableSet(copy);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getServerId() { return serverId; }
    public UUID getInstanceId() { return instanceId; }
    public Set<String> getModes() { return modes; }
}
