package com.starfinplugins.minigames.bungee;

import java.util.Arrays;
import java.util.Collections;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

final class JoinCommand extends Command implements TabExecutor {
    private final StarfinMinigamesBungee plugin;

    JoinCommand(StarfinMinigamesBungee plugin) { super("smjoin"); this.plugin = plugin; }

    @Override public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            plugin.send(sender, "&cApenas jogadores podem entrar em partidas.");
            return;
        }
        if (args.length < 1 || args.length > 2
                || !args[0].matches("[A-Za-z0-9_-]{1,32}")
                || (args.length == 2 && !args[1].matches("[A-Za-z0-9_-]{1,16}"))) {
            plugin.send(sender, "&eUso: /smjoin <minigame> [modalidade]");
            return;
        }
        String game = args[0].toLowerCase(java.util.Locale.ROOT);
        if (!"bedwars".equals(game)) {
            plugin.send(sender, "&cMinigame ainda nao registrado no catalogo do proxy.");
            return;
        }
        plugin.requestJoin((ProxiedPlayer) sender, game,
            args.length == 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : null);
    }

    @Override public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && "bedwars".startsWith(args[0].toLowerCase(java.util.Locale.ROOT)))
            return Collections.singletonList("bedwars");
        if (args.length == 2 && "bedwars".equalsIgnoreCase(args[0]))
            return Arrays.asList("solo", "duo", "trio", "squad");
        return Collections.emptyList();
    }
}
