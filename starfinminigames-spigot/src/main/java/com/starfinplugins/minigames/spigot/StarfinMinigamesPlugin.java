package com.starfinplugins.minigames.spigot;

import com.starfinplugins.minigames.api.MinigameRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class StarfinMinigamesPlugin extends JavaPlugin {
    private SpigotMinigameRegistry registry;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (!getServer().getBukkitVersion().startsWith("1.8.8"))
                throw new IllegalStateException("StarfinMinigames requer Spigot/Paper 1.8.8");
            String serverId = getConfig().getString("server-id", "local-01");
            registry = new SpigotMinigameRegistry(serverId);
            getServer().getServicesManager().register(MinigameRegistry.class, registry, this, ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(registry, this);
            boolean preventHunger = getConfig().getBoolean("lobby.prevent-hunger", false);
            boolean levelView = getConfig().getBoolean("lobby.level-spawn-view", false);
            if (preventHunger || levelView) {
                getServer().getPluginManager().registerEvents(
                    new LobbyRulesListener(this, preventHunger, levelView), this);
                if (preventHunger)
                    for (Player player : getServer().getOnlinePlayers()) player.setFoodLevel(20);
            }
            PluginCommand command = getCommand("sminigames");
            if (command == null) throw new IllegalStateException("Comando sminigames ausente");
            MinigameCommand executor = new MinigameCommand(this, registry);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            getLogger().info("API " + MinigameRegistry.API_VERSION + " pronta em " + serverId);
        } catch (RuntimeException | LinkageError error) {
            getLogger().severe("Falha ao iniciar StarfinMinigames: " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        HandlerList.unregisterAll(this);
        if (registry != null) registry.close();
        registry = null;
    }

    public MinigameRegistry getRegistry() { return registry; }

    private static final class LobbyRulesListener implements Listener {
        private final StarfinMinigamesPlugin plugin;
        private final boolean preventHunger;
        private final boolean levelView;

        LobbyRulesListener(StarfinMinigamesPlugin plugin, boolean preventHunger, boolean levelView) {
            this.plugin = plugin;
            this.preventHunger = preventHunger;
            this.levelView = levelView;
        }

        @EventHandler public void onJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            if (preventHunger) player.setFoodLevel(20);
            if (levelView) plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.teleport(level(player.getLocation()));
            });
        }

        @EventHandler public void onRespawn(PlayerRespawnEvent event) {
            if (levelView) event.setRespawnLocation(level(event.getRespawnLocation()));
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            if (levelView && event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                    && event.getTo() != null) event.setTo(level(event.getTo()));
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onFoodLevelChange(FoodLevelChangeEvent event) {
            if (!preventHunger) return;
            event.setCancelled(true);
            if (event.getEntity() instanceof Player) ((Player) event.getEntity()).setFoodLevel(20);
        }

        private static Location level(Location location) {
            Location copy = location.clone();
            copy.setPitch(0.0F);
            return copy;
        }
    }
}
