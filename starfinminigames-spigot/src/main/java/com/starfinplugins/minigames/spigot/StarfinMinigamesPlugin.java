package com.starfinplugins.minigames.spigot;

import com.starfinplugins.minigames.api.MinigameRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
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
}
