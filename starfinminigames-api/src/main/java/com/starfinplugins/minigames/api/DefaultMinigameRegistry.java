package com.starfinplugins.minigames.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Pure Java registry. The Spigot wrapper adds plugin ownership and main-thread checks. */
public final class DefaultMinigameRegistry implements MinigameRegistry, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DefaultMinigameRegistry.class.getName());
    private final String serverId;
    private final UUID instanceId;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final List<MinigameLifecycleListener> listeners = new ArrayList<MinigameLifecycleListener>();
    private boolean closed;

    public DefaultMinigameRegistry(String serverId, UUID instanceId) {
        this.serverId = Ids.stable(serverId, "server id");
        if (instanceId == null) throw new IllegalArgumentException("instance id is required");
        this.instanceId = instanceId;
    }

    @Override public String getServerId() { return serverId; }
    @Override public UUID getInstanceId() { return instanceId; }

    @Override public MinigameRegistration register(MinigameProvider provider) {
        if (provider == null || provider.descriptor() == null) throw new IllegalArgumentException("provider is required");
        MinigameDescriptor descriptor = provider.descriptor();
        if (!serverId.equals(descriptor.getServerId()) || !instanceId.equals(descriptor.getInstanceId()))
            throw new IllegalArgumentException("provider server/instance identity does not match this registry");
        Entry entry = new Entry(provider, descriptor);
        synchronized (this) {
            ensureOpen();
            if (entries.containsKey(descriptor.getId()))
                throw new IllegalArgumentException("minigame id already registered: " + descriptor.getId());
            entries.put(descriptor.getId(), entry);
        }
        dispatch(event(MinigameEvent.Type.MINIGAME_REGISTERED, descriptor.getId()));
        return entry;
    }

    @Override public MinigameSubscription subscribe(MinigameLifecycleListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        synchronized (this) { ensureOpen(); listeners.add(listener); }
        return () -> { synchronized (DefaultMinigameRegistry.this) { listeners.remove(listener); } };
    }

    @Override public synchronized List<MinigameDescriptor> minigames() {
        List<MinigameDescriptor> result = new ArrayList<MinigameDescriptor>();
        for (Entry entry : entries.values()) result.add(entry.currentDescriptor());
        return Collections.unmodifiableList(result);
    }

    @Override public synchronized MinigameDescriptor find(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.currentDescriptor();
    }

    @Override public List<MinigameArenaSnapshot> arenas(String id) {
        Entry entry = entry(id);
        List<MinigameArenaSnapshot> result = entry == null ? Collections.<MinigameArenaSnapshot>emptyList() : immutable(entry.provider.arenas());
        for (MinigameArenaSnapshot arena : result)
            validateSnapshot(arena == null ? null : arena.getMinigameId(), arena == null ? null : arena.getServerId(), id);
        return result;
    }

    @Override public List<MinigameMatchSnapshot> matches(String id) {
        Entry entry = entry(id);
        List<MinigameMatchSnapshot> result = entry == null ? Collections.<MinigameMatchSnapshot>emptyList() : immutable(entry.provider.matches());
        for (MinigameMatchSnapshot match : result)
            validateSnapshot(match == null ? null : match.getMinigameId(), match == null ? null : match.getServerId(), id);
        return result;
    }

    @Override public List<MinigamePlayerSnapshot> players(String id) {
        Entry entry = entry(id);
        List<MinigamePlayerSnapshot> result = entry == null ? Collections.<MinigamePlayerSnapshot>emptyList() : immutable(entry.provider.players());
        for (MinigamePlayerSnapshot player : result)
            validateSnapshot(player == null ? null : player.getMinigameId(), player == null ? null : player.getServerId(), id);
        return result;
    }

    private void validateSnapshot(String gameId, String snapshotServerId, String requestedId) {
        if (!requestedId.equals(gameId) || !serverId.equals(snapshotServerId))
            throw new IllegalStateException("provider returned a snapshot outside its registered game/server");
    }

    private synchronized Entry entry(String id) { return entries.get(id); }

    private static <T> List<T> immutable(Collection<T> source) {
        return source == null ? Collections.<T>emptyList()
            : Collections.unmodifiableList(new ArrayList<T>(source));
    }

    private static MinigameEvent event(MinigameEvent.Type type, String id) {
        return new MinigameEvent(type, id, null, null, System.currentTimeMillis());
    }

    private void dispatch(MinigameEvent event) {
        List<MinigameLifecycleListener> copy;
        synchronized (this) { copy = new ArrayList<MinigameLifecycleListener>(listeners); }
        for (MinigameLifecycleListener listener : copy) {
            try { listener.onMinigameEvent(event); }
            catch (RuntimeException error) { LOG.log(Level.WARNING, "Minigame listener failed", error); }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("registry is closed");
    }

    @Override public void close() {
        List<Entry> copy;
        synchronized (this) {
            if (closed) return;
            closed = true;
            copy = new ArrayList<Entry>(entries.values());
        }
        for (Entry entry : copy) entry.close();
        synchronized (this) { listeners.clear(); }
    }

    private final class Entry implements MinigameRegistration {
        private final MinigameProvider provider;
        private final MinigameDescriptor descriptor;

        private Entry(MinigameProvider provider, MinigameDescriptor descriptor) {
            this.provider = provider;
            this.descriptor = descriptor;
        }

        private MinigameDescriptor currentDescriptor() {
            MinigameDescriptor current = provider.descriptor();
            if (current == null || !descriptor.getId().equals(current.getId())
                    || !serverId.equals(current.getServerId())
                    || !instanceId.equals(current.getInstanceId()))
                throw new IllegalStateException("provider changed its registered identity");
            return current;
        }

        @Override public String getMinigameId() { return descriptor.getId(); }

        @Override public void publish(MinigameEvent event) {
            if (event == null || !descriptor.getId().equals(event.getMinigameId())
                    || event.getType() == MinigameEvent.Type.MINIGAME_REGISTERED
                    || event.getType() == MinigameEvent.Type.MINIGAME_UNREGISTERED)
                throw new IllegalArgumentException("invalid owner event");
            synchronized (DefaultMinigameRegistry.this) {
                if (entries.get(descriptor.getId()) != this) return;
            }
            dispatch(event);
        }

        @Override public void close() {
            synchronized (DefaultMinigameRegistry.this) {
                if (entries.get(descriptor.getId()) != this) return;
                entries.remove(descriptor.getId());
            }
            dispatch(event(MinigameEvent.Type.MINIGAME_UNREGISTERED, descriptor.getId()));
        }
    }
}
