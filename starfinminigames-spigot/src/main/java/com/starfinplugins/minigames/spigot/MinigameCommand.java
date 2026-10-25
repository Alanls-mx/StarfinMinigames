package com.starfinplugins.minigames.spigot;

import com.starfinplugins.minigames.api.MinigameDescriptor;
import com.starfinplugins.minigames.api.MinigameArenaSnapshot;
import com.starfinplugins.minigames.api.MinigameMatchSnapshot;
import com.starfinplugins.minigames.api.MinigameRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

final class MinigameCommand implements CommandExecutor, TabCompleter {
    private final StarfinMinigamesPlugin plugin;
    private final MinigameRegistry registry;

    MinigameCommand(StarfinMinigamesPlugin plugin, MinigameRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("starfinminigames.admin")) {
            sender.sendMessage(ChatColor.RED + "Sem permissao.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        try {
            switch (sub) {
                case "status":
                    sender.sendMessage(ChatColor.AQUA + "StarfinMinigames API " + MinigameRegistry.API_VERSION);
                    sender.sendMessage(ChatColor.GRAY + "Servidor: " + registry.getServerId()
                        + " | Instancia: " + registry.getInstanceId());
                    sender.sendMessage(ChatColor.GRAY + "Minigames: " + registry.minigames().size()
                        + " | Core: " + (plugin.getServer().getPluginManager().isPluginEnabled("StarfinCore") ? "ativo" : "ausente"));
                    return true;
                case "list":
                    if (registry.minigames().isEmpty()) sender.sendMessage(ChatColor.YELLOW + "Nenhum minigame registrado.");
                    for (MinigameDescriptor descriptor : registry.minigames())
                        sender.sendMessage(ChatColor.AQUA + descriptor.getId() + ChatColor.GRAY + " - "
                            + descriptor.getDisplayName() + " | modos: " + descriptor.getModes());
                    return true;
                case "info":
                    if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "Uso: /sminigames info <minigame>"); return true; }
                    MinigameDescriptor descriptor = registry.find(args[1]);
                    if (descriptor == null) { sender.sendMessage(ChatColor.RED + "Minigame nao registrado."); return true; }
                    sender.sendMessage(ChatColor.AQUA + descriptor.getDisplayName() + ChatColor.GRAY
                        + " (" + descriptor.getId() + ") | Modos: " + descriptor.getModes());
                    List<MinigameArenaSnapshot> arenas = registry.arenas(descriptor.getId());
                    List<MinigameMatchSnapshot> matches = registry.matches(descriptor.getId());
                    sender.sendMessage(ChatColor.GRAY + "Arenas: " + arenas.size()
                        + " | Partidas: " + matches.size()
                        + " | Jogadores: " + registry.players(descriptor.getId()).size());
                    for (MinigameArenaSnapshot arena : arenas)
                        sender.sendMessage(ChatColor.GRAY + "  Arena " + arena.getArenaId() + " ["
                            + arena.getMode() + "/" + arena.getGroup() + "] " + arena.getPlayers()
                            + "/" + arena.getMaxPlayers() + (arena.isJoinable() ? " aberta" : " fechada"));
                    for (MinigameMatchSnapshot match : matches)
                        sender.sendMessage(ChatColor.GRAY + "  Partida " + match.getId() + " ["
                            + match.getArenaId() + "] " + match.getState() + " "
                            + match.getConnectedPlayers() + "/" + match.getMaxPlayers());
                    return true;
                case "reload":
                    plugin.reloadConfig();
                    String configured = plugin.getConfig().getString("server-id", "local-01");
                    if (!registry.getServerId().equals(configured))
                        sender.sendMessage(ChatColor.YELLOW + "server-id mudou; reinicie o servidor para aplicar a identidade.");
                    else sender.sendMessage(ChatColor.GREEN + "Configuracao validada; registros preservados.");
                    return true;
                default:
                    sender.sendMessage(ChatColor.YELLOW + "Uso: /sminigames <status|list|info|reload>");
                    return true;
            }
        } catch (RuntimeException error) {
            sender.sendMessage(ChatColor.RED + "Diagnostico indisponivel: " + error.getClass().getSimpleName());
            plugin.getLogger().warning("Falha no comando sminigames: " + error.getMessage());
            return true;
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("starfinminigames.admin")) return Collections.emptyList();
        if (args.length == 0) return Arrays.asList("status", "list", "info", "reload");
        List<String> candidates = new ArrayList<String>();
        if (args.length == 1) candidates.addAll(Arrays.asList("status", "list", "info", "reload"));
        else if (args.length == 2 && "info".equalsIgnoreCase(args[0]))
            for (MinigameDescriptor game : registry.minigames()) candidates.add(game.getId());
        List<String> result = new ArrayList<String>();
        String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        for (String candidate : candidates) if (candidate.startsWith(prefix)) result.add(candidate);
        return result;
    }
}
