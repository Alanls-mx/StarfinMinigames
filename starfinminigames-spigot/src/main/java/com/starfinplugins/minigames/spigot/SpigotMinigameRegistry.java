package com.starfinplugins.minigames.spigot;

import com.starfinplugins.minigames.api.DefaultMinigameRegistry;
import com.starfinplugins.minigames.api.MinigameArenaSnapshot;
import com.starfinplugins.minigames.api.MinigameDescriptor;
import com.starfinplugins.minigames.api.MinigameEvent;
import com.starfinplugins.minigames.api.MinigameLifecycleListener;
import com.starfinplugins.minigames.api.MinigameMatchSnapshot;
import com.starfinplugins.minigames.api.MinigamePlayerSnapshot;
import com.starfinplugins.minigames.api.MinigameProvider;
import com.starfinplugins.minigames.api.MinigameRegistration;
import com.starfinplugins.minigames.api.MinigameRegistry;
import com.starfinplugins.minigames.api.MinigameSubscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Bukkit ownership and main-thread boundary around the pure Java registry. */
public final class SpigotMinigameRegistry implements MinigameRegistry, Listener, AutoCloseable {
    private final DefaultMinigameRegistry delegate;
    private final Map<Plugin, List<MinigameRegistration>> registrations =
        new HashMap<Plugin, List<MinigameRegistration>>();
    private final Map<Plugin, List<MinigameSubscription>> subscriptions =
        new HashMap<Plugin, List<MinigameSubscription>>();

    public SpigotMinigameRegistry(String serverId) {
        delegate = new DefaultMinigameRegistry(serverId, UUID.randomUUID());
    }

    @Override public String getServerId() { return delegate.getServerId(); }
    @Override public UUID getInstanceId() { return delegate.getInstanceId(); }

    @Override public MinigameRegistration register(MinigameProvider provider) {
        primaryThread();
        if (provider == null) throw new IllegalArgumentException("provider is required");
        Plugin owner = JavaPlugin.getProvidingPlugin(provider.getClass());
        if (!owner.isEnabled()) throw new IllegalStateException("provider plugin is disabled");
        MinigameRegistration registration = delegate.register(provider);
        MinigameRegistration owned = new MinigameRegistration() {
            private boolean closed;
            @Override public String getMinigameId() { return registration.getMinigameId(); }
            @Override public void publish(MinigameEvent event) {
                primaryThread();
                registration.publish(event);
            }
            @Override public void close() {
                primaryThread();
                if (closed) return;
                closed = true;
                registration.close();
                untrack(registrations, owner, this);
            }
        };
        track(registrations, owner, owned);
        return owned;
    }

    @Override public MinigameSubscription subscribe(MinigameLifecycleListener listener) {
        primaryThread();
        if (listener == null) throw new IllegalArgumentException("listener is required");
        Plugin owner = JavaPlugin.getProvidingPlugin(listener.getClass());
        if (!owner.isEnabled()) throw new IllegalStateException("listener plugin is disabled");
        MinigameSubscription subscription = delegate.subscribe(listener);
        MinigameSubscription owned = new MinigameSubscription() {
            private boolean closed;
            @Override public void close() {
                primaryThread();
                if (closed) return;
                closed = true;
                subscription.close();
                untrack(subscriptions, owner, this);
            }
        };
        track(subscriptions, owner, owned);
        return owned;
    }

    private static <T> void track(Map<Plugin, List<T>> map, Plugin owner, T handle) {
        List<T> handles = map.get(owner);
        if (handles == null) { handles = new ArrayList<T>(); map.put(owner, handles); }
        handles.add(handle);
    }

    private static <T> void untrack(Map<Plugin, List<T>> map, Plugin owner, T handle) {
        List<T> handles = map.get(owner);
        if (handles == null) return;
        handles.remove(handle);
        if (handles.isEmpty()) map.remove(owner);
    }

    @Override public List<MinigameDescriptor> minigames() { primaryThread(); return delegate.minigames(); }
    @Override public MinigameDescriptor find(String id) { primaryThread(); return delegate.find(id); }
    @Override public List<MinigameArenaSnapshot> arenas(String id) { primaryThread(); return delegate.arenas(id); }
    @Override public List<MinigameMatchSnapshot> matches(String id) { primaryThread(); return delegate.matches(id); }
    @Override public List<MinigamePlayerSnapshot> players(String id) { primaryThread(); return delegate.players(id); }

    @EventHandler public void onPluginDisabled(PluginDisableEvent event) {
        closeOwner(event.getPlugin());
    }

    private void closeOwner(Plugin owner) {
        primaryThread();
        List<MinigameRegistration> owned = registrations.remove(owner);
        if (owned != null) for (MinigameRegistration handle : owned) handle.close();
        List<MinigameSubscription> listening = subscriptions.remove(owner);
        if (listening != null) for (MinigameSubscription handle : listening) handle.close();
    }

    @Override public void close() {
        primaryThread();
        for (Plugin owner : new ArrayList<Plugin>(registrations.keySet())) closeOwner(owner);
        for (Plugin owner : new ArrayList<Plugin>(subscriptions.keySet())) closeOwner(owner);
        delegate.close();
    }

    static void primaryThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Minigame API must be used on the Bukkit primary thread");
    }
}
