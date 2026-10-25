package com.starfinplugins.minigames.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;

public class DefaultMinigameRegistryTest {
    private final UUID instance = UUID.randomUUID();

    @Test public void registrationDuplicateAndRemoval() {
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        MutableProvider game = new MutableProvider("bedwars");
        List<MinigameEvent.Type> seen = new ArrayList<MinigameEvent.Type>();
        registry.subscribe(event -> seen.add(event.getType()));
        MinigameRegistration handle = registry.register(game);
        Assert.assertEquals("bedwars", registry.find("bedwars").getId());
        Assert.assertThrows(IllegalArgumentException.class, () -> registry.register(game));
        handle.close();
        handle.close();
        Assert.assertNull(registry.find("bedwars"));
        Assert.assertEquals(Arrays.asList(MinigameEvent.Type.MINIGAME_REGISTERED,
            MinigameEvent.Type.MINIGAME_UNREGISTERED), seen);
    }

    @Test public void defensiveSnapshotsAndUuidIdentity() {
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        Set<UUID> mutableIds = new LinkedHashSet<UUID>();
        mutableIds.add(playerId);
        MinigameMatchSnapshot match = new MinigameMatchSnapshot(matchId, "bedwars", "lighthouse",
            "bedwars-01", MinigameMatchState.WAITING, 2, 1, mutableIds, 0);
        mutableIds.clear();
        Assert.assertEquals(Collections.singleton(playerId), match.getParticipantIds());
        Assert.assertThrows(UnsupportedOperationException.class, () -> match.getParticipantIds().clear());
        MinigamePlayerSnapshot player = new MinigamePlayerSnapshot(playerId, "bedwars", matchId,
            "lighthouse", "bedwars-01", MinigamePlayerSnapshot.Role.PLAYER);
        Assert.assertEquals(playerId, player.getPlayerId());
        MutableProvider provider = new MutableProvider("bedwars");
        provider.matches.add(match);
        provider.players.add(player);
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        registry.register(provider);
        List<MinigameMatchSnapshot> copy = registry.matches("bedwars");
        provider.matches.clear();
        Assert.assertEquals(1, copy.size());
        Assert.assertThrows(UnsupportedOperationException.class, () -> copy.clear());
    }

    @Test public void stateChangesAndRemovalAreVisibleWithoutLiveObjectLeak() {
        MutableProvider provider = new MutableProvider("bedwars");
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        MinigameRegistration handle = registry.register(provider);
        UUID id = UUID.randomUUID();
        provider.matches.add(match(id, MinigameMatchState.WAITING));
        Assert.assertEquals(MinigameMatchState.WAITING, registry.matches("bedwars").get(0).getState());
        provider.matches.set(0, match(id, MinigameMatchState.IN_GAME));
        Assert.assertEquals(MinigameMatchState.IN_GAME, registry.matches("bedwars").get(0).getState());
        provider.matches.clear();
        Assert.assertTrue(registry.matches("bedwars").isEmpty());
        handle.close();
        Assert.assertTrue(registry.minigames().isEmpty());
    }

    @Test public void minigamesAreIsolatedAndModesMayBeAbsent() {
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        MutableProvider bedwars = new MutableProvider("bedwars");
        MutableProvider skywars = new MutableProvider("skywars");
        bedwars.matches.add(match(UUID.randomUUID(), MinigameMatchState.STARTING));
        registry.register(bedwars);
        registry.register(skywars);
        Assert.assertEquals(1, registry.matches("bedwars").size());
        Assert.assertTrue(registry.matches("skywars").isEmpty());
        Assert.assertTrue(registry.find("skywars").getModes().isEmpty());
        Assert.assertTrue(registry.matches("missing").isEmpty());
    }

    @Test public void rejectsSnapshotsOutsideProviderIdentity() {
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        MutableProvider provider = new MutableProvider("bedwars");
        registry.register(provider);
        provider.arenas.add(new MinigameArenaSnapshot("skywars", "arena", "Arena", "solo",
            "default", "bedwars-01", 1, 2, 0, true, true, null));
        Assert.assertThrows(IllegalStateException.class, () -> registry.arenas("bedwars"));
        provider.arenas.clear();
        provider.matches.add(new MinigameMatchSnapshot(UUID.randomUUID(), "bedwars", "arena",
            "other-server", MinigameMatchState.WAITING, 2, 0, Collections.<UUID>emptySet(), 0));
        Assert.assertThrows(IllegalStateException.class, () -> registry.matches("bedwars"));
        provider.matches.clear();
        provider.players.add(new MinigamePlayerSnapshot(UUID.randomUUID(), "skywars",
            UUID.randomUUID(), "arena", "bedwars-01", MinigamePlayerSnapshot.Role.PLAYER));
        Assert.assertThrows(IllegalStateException.class, () -> registry.players("bedwars"));
    }

    @Test public void shutdownAndRestartLeaveNoOrphans() {
        DefaultMinigameRegistry old = new DefaultMinigameRegistry("bedwars-01", instance);
        MinigameRegistration handle = old.register(new MutableProvider("bedwars"));
        old.close();
        handle.publish(new MinigameEvent(MinigameEvent.Type.MATCH_CREATED, "bedwars",
            match(UUID.randomUUID(), MinigameMatchState.WAITING), null, System.currentTimeMillis()));
        Assert.assertTrue(old.minigames().isEmpty());
        Assert.assertThrows(IllegalStateException.class, () -> old.register(new MutableProvider("skywars")));
        UUID nextInstance = UUID.randomUUID();
        DefaultMinigameRegistry next = new DefaultMinigameRegistry("bedwars-01", nextInstance);
        next.register(new MutableProvider("bedwars", nextInstance));
        Assert.assertEquals(1, next.minigames().size());
    }

    @Test public void listenerFailureDoesNotBlockRegistrationOrMatchEvents() {
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        registry.subscribe(event -> { throw new IllegalStateException("consumer failed"); });
        List<MinigameEvent.Type> received = new ArrayList<MinigameEvent.Type>();
        registry.subscribe(event -> received.add(event.getType()));
        MinigameRegistration handle = registry.register(new MutableProvider("bedwars"));
        handle.publish(new MinigameEvent(MinigameEvent.Type.MATCH_STARTED, "bedwars",
            match(UUID.randomUUID(), MinigameMatchState.IN_GAME), null, 1));
        Assert.assertEquals(Arrays.asList(MinigameEvent.Type.MINIGAME_REGISTERED,
            MinigameEvent.Type.MATCH_STARTED), received);
    }

    @Test public void modeDiscoveryRefreshesWithoutReregistration() {
        MutableProvider provider = new MutableProvider("bedwars");
        DefaultMinigameRegistry registry = new DefaultMinigameRegistry("bedwars-01", instance);
        registry.register(provider);
        Assert.assertTrue(registry.find("bedwars").getModes().isEmpty());
        provider.modes.add("solo");
        Assert.assertEquals(Collections.singleton("solo"), registry.find("bedwars").getModes());
    }

    private MinigameMatchSnapshot match(UUID id, MinigameMatchState state) {
        return new MinigameMatchSnapshot(id, "bedwars", "lighthouse", "bedwars-01",
            state, 2, 0, Collections.<UUID>emptySet(), 0);
    }

    private final class MutableProvider implements MinigameProvider {
        final String id;
        final UUID providerInstance;
        final Set<String> modes = new LinkedHashSet<String>();
        final List<MinigameArenaSnapshot> arenas = new ArrayList<MinigameArenaSnapshot>();
        final List<MinigameMatchSnapshot> matches = new ArrayList<MinigameMatchSnapshot>();
        final List<MinigamePlayerSnapshot> players = new ArrayList<MinigamePlayerSnapshot>();
        MutableProvider(String id) { this(id, instance); }
        MutableProvider(String id, UUID providerInstance) {
            this.id = id;
            this.providerInstance = providerInstance;
        }
        @Override public MinigameDescriptor descriptor() {
            return new MinigameDescriptor(id, id, "bedwars-01", providerInstance, modes);
        }
        @Override public Collection<MinigameArenaSnapshot> arenas() { return arenas; }
        @Override public Collection<MinigameMatchSnapshot> matches() { return matches; }
        @Override public Collection<MinigamePlayerSnapshot> players() { return players; }
    }
}
