package com.starfinplugins.minigames.bungee;

import java.util.Arrays;
import java.util.Collections;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

final class RankedCommand extends Command implements TabExecutor {
    private final RankedQueueService queue;
    RankedCommand(RankedQueueService queue) { super("smranked"); this.queue = queue; }
    @Override public void execute(CommandSender sender, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sender.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                "§b/smranked join <solo|duo|trio|squad>, leave, status"));
            return;
        }
        if ("admin".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission("starfinminigames.ranked.admin")) {
                sender.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText("§cSem permissao."));
                return;
            }
            String topic = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
            if (!Arrays.asList("status", "queues", "players", "matches", "inspect", "opponents", "reload").contains(topic)
                    || (("inspect".equals(topic) || "opponents".equals(topic)) && args.length < 3)) {
                sender.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                    "§eUso: /smranked admin <status|queues|players|matches|inspect UUID|opponents UUID>"));
                return;
            }
            if ("reload".equals(topic)) {
                queue.reload(sender);
                return;
            }
            queue.adminStatus(sender, topic, args.length > 2 ? args[2] : null); return;
        }
        if (!(sender instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) sender;
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "join":
                if (args.length == 2) queue.join(player, args[1]);
                else player.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                    "§eUso: /smranked join <solo|duo|trio|squad>"));
                break;
            case "leave": queue.leave(player); break;
            case "status": queue.status(player); break;
            default: execute(sender, new String[] {"help"});
        }
    }
    @Override public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return Arrays.asList("join", "leave", "status", "help", "admin");
        if (args.length == 2 && "join".equalsIgnoreCase(args[0]))
            return Arrays.asList("solo", "duo", "trio", "squad");
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])
                && sender.hasPermission("starfinminigames.ranked.admin"))
            return Arrays.asList("status", "queues", "players", "matches", "inspect", "opponents", "reload");
        return Collections.emptyList();
    }
}
