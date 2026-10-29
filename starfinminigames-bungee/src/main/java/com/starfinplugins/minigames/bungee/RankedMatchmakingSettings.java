package com.starfinplugins.minigames.bungee;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.md_5.bungee.config.Configuration;

/** Proxy-only competitive rules. Rating identity comes from BedWars' published policy. */
final class RankedMatchmakingSettings {
    final boolean enabled, balance, allowSoloParty, requireStarfinLogin;
    final Set<String> modes, arenaGroups;
    final int initialRange, expansionSeconds, expansionAmount, maximumRange;
    final int maximumPartyDifference, maximumTeamDifference, heartbeatSeconds, staleSeconds;
    final int transferSeconds, allocationSeconds;

    RankedMatchmakingSettings(Configuration yaml) {
        String prefix = "ranked-matchmaking.";
        enabled = yaml.getBoolean(prefix + "enabled", false);
        requireStarfinLogin = yaml.getBoolean(prefix + "auth.require-starfin-login", true);
        Set<String> selected = new HashSet<String>();
        for (String mode : new String[] { "solo", "duo", "trio", "squad", "custom" })
            if (yaml.getBoolean(prefix + "modes." + mode, !"custom".equals(mode))) selected.add(mode);
        modes = Collections.unmodifiableSet(selected);
        Set<String> groups = new HashSet<String>(yaml.getStringList(prefix + "arenas.allowed-groups"));
        if (groups.isEmpty()) groups.add("ranked");
        for (String group : groups)
            if (group == null || !group.matches("[A-Za-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("Grupo de arena ranqueada invalido");
        arenaGroups = Collections.unmodifiableSet(groups);
        initialRange = yaml.getInt(prefix + "search.initial-rating-range", 250);
        expansionSeconds = yaml.getInt(prefix + "search.expansion-interval-seconds", 30);
        expansionAmount = yaml.getInt(prefix + "search.expansion-amount", 75);
        maximumRange = yaml.getInt(prefix + "search.maximum-rating-range", 600);
        maximumPartyDifference = yaml.getInt(prefix + "party.maximum-rating-difference", 400);
        allowSoloParty = yaml.getBoolean(prefix + "party.allow-solo-party", false);
        balance = yaml.getBoolean(prefix + "teams.balance-enabled", true);
        maximumTeamDifference = yaml.getInt(prefix + "teams.maximum-average-rating-difference", 250);
        heartbeatSeconds = yaml.getInt(prefix + "queue.heartbeat-seconds", 5);
        staleSeconds = yaml.getInt(prefix + "queue.stale-entry-seconds", 30);
        transferSeconds = yaml.getInt(prefix + "queue.transfer-seconds", 60);
        allocationSeconds = yaml.getInt(prefix + "queue.allocation-seconds", 120);
        if (initialRange < 0 || initialRange > 5000 || expansionSeconds < 5
                || expansionSeconds > 600 || expansionAmount < 1 || expansionAmount > 1000
                || maximumRange < initialRange || maximumRange > 5000
                || maximumPartyDifference < 0 || maximumPartyDifference > 5000
                || maximumTeamDifference < 0 || maximumTeamDifference > 5000
                || heartbeatSeconds < 1 || heartbeatSeconds > 30
                || staleSeconds < heartbeatSeconds * 2 || staleSeconds > 300
                || transferSeconds < 15 || transferSeconds > 300
                || allocationSeconds < transferSeconds || allocationSeconds > 600)
            throw new IllegalArgumentException("ranked-matchmaking possui limites invalidos");
        if (allowSoloParty)
            throw new IllegalArgumentException("Party em Solo ranqueado ainda nao possui isolamento entre partidas");
    }

    int range(long joinedAt, long now) {
        long elapsed = Math.max(0L, now - joinedAt) / (expansionSeconds * 1000L);
        return (int) Math.min(maximumRange, initialRange + elapsed * expansionAmount);
    }
}
