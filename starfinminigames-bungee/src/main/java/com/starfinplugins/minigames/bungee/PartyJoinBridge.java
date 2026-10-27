package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.social.api.PartyResult;
import com.starfinplugins.social.api.PartyService;
import com.starfinplugins.social.api.PartyTransferPlan;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

/** Optional StarfinSocial bridge. Loaded only if the social proxy plugin is present. */
final class PartyJoinBridge {
    private PartyJoinBridge() { }

    static void request(StarfinMinigamesBungee plugin, ProxiedPlayer leader,
                        String game, String mode) {
        PartyService social = service(plugin);
        if (social == null) {
            plugin.sendKey(leader,"party-unavailable","&cNao foi possivel consultar sua Party.");
            return;
        }
        UUID leaderId = leader.getUniqueId();
        social.getParty(leaderId).whenComplete((party, error) -> {
            if (plugin.proxy().getPlayer(leaderId) != leader) return;
            if (error != null) { unavailable(plugin,leader); return; }
            if (party == null) { plugin.requestSoloJoin(leader,game,mode); return; }
            social.planTransfer(leaderId,game,64).whenComplete((result, planError) -> {
                if (plugin.proxy().getPlayer(leaderId) != leader) return;
                if (planError != null || result == null) { unavailable(plugin,leader); return; }
                if (!result.succeeded()) {
                    if (result.code == PartyResult.Code.NOT_LEADER)
                        plugin.sendKey(leader,"party-leader-only",
                            "&eApenas o lider da Party pode procurar partida para o grupo.");
                    else unavailable(plugin,leader);
                    return;
                }
                PartyTransferPlan plan = result.value;
                if (plan == null || plan.onlineMembers.isEmpty()
                        || !plan.onlineMembers.contains(leaderId)
                        || !party.id.equals(plan.partyId)) { unavailable(plugin,leader); return; }
                if (plan.onlineMembers.size() == 1) {
                    plugin.requestSoloJoin(leader,game,mode); return;
                }
                if (!plugin.settings().allowSoloParty && "solo".equalsIgnoreCase(mode)) {
                    plugin.sendKey(leader,"party-solo-disabled",
                        "&eParties nao podem entrar juntas no modo Solo.");
                    return;
                }
                Map<UUID,ProxiedPlayer> members = localMembers(plugin,plan.onlineMembers);
                if (members == null) {
                    plugin.sendKey(leader,"party-members-unavailable",
                        "&eTodos os membros online da Party precisam estar neste proxy e no lobby.");
                    return;
                }
                List<UUID> ids = new ArrayList<UUID>(plan.onlineMembers);
                if (!plugin.acquireGroup(ids)) {
                    plugin.sendKey(leader,"search-in-progress",
                        "&eUm membro ja tem uma busca ou transferencia em andamento.");
                    return;
                }
                if (!plugin.submitIo(() -> reserveAndConnect(plugin,social,leader,
                        game,mode,plan,ids,members))) {
                    plugin.releaseGroup(ids);
                    plugin.sendKey(leader,"proxy-busy",
                        "&cO proxy esta ocupado. Tente novamente em instantes.");
                }
            });
        });
    }

    private static PartyService service(StarfinMinigamesBungee plugin) {
        Plugin social = plugin.proxy().getPluginManager().getPlugin("StarfinSocial");
        if (social == null) return null;
        try {
            Object value = social.getClass().getMethod("partyService").invoke(social);
            return value instanceof PartyService ? (PartyService) value : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException error) {
            plugin.getLogger().warning("API de Party indisponivel ("
                + error.getClass().getSimpleName() + ").");
            return null;
        }
    }

    private static Map<UUID,ProxiedPlayer> localMembers(StarfinMinigamesBungee plugin,
                                                          List<UUID> ids) {
        Map<UUID,ProxiedPlayer> result = new LinkedHashMap<UUID,ProxiedPlayer>();
        for (UUID id : ids) {
            ProxiedPlayer player = plugin.proxy().getPlayer(id);
            if (player == null || player.getServer() == null)
                return null;
            result.put(id,player);
        }
        return result;
    }

    private static boolean allowedSource(StarfinMinigamesBungee plugin,
            ProxiedPlayer player, ServerInfo destination) {
        if (player == null || player.getServer() == null) return false;
        String current = player.getServer().getInfo().getName();
        return plugin.settings().lobbyServer.equals(current)
            || destination.getName().equals(current);
    }

    private static void reserveAndConnect(StarfinMinigamesBungee plugin, PartyService social,
            ProxiedPlayer leader, String game, String mode, PartyTransferPlan plan,
            List<UUID> ids, Map<UUID,ProxiedPlayer> members) {
        List<ReservationSnapshot> rows = Collections.emptyList();
        try {
            BungeeDatabase db = plugin.database();
            if (db == null) throw new IllegalStateException("Catalogo indisponivel");
            rows = db.reserveGroup(ids,game,mode,plan.partyId,plan.revision,
                leader.getUniqueId());
            if (rows.isEmpty()) {
                int capacity = db.maximumTeamSize(game,mode);
                if (capacity > 1 && capacity < ids.size())
                    plugin.sendKey(leader,"party-oversized",
                        "&cSua Party possui {size} jogadores, mas cada equipe suporta no maximo {capacity}.",
                        "size",Integer.toString(ids.size()),"capacity",Integer.toString(capacity));
                else plugin.sendKey(leader,"party-no-team",
                    "&cNao existe uma equipe com vagas suficientes para sua Party.");
                plugin.releaseGroup(ids); return;
            }
            ReservationSnapshot first = rows.get(0);
            String name = plugin.settings().serverNames.get(first.getServerId());
            ServerInfo destination = name == null ? null : plugin.proxy().getServerInfo(name);
            PartyResult<PartyTransferPlan> checked = social.planTransfer(leader.getUniqueId(),
                game,rows.size()).get(5,TimeUnit.SECONDS);
            if (destination == null || checked == null || !checked.succeeded()
                    || checked.value == null || !checked.value.partyId.equals(plan.partyId)
                    || checked.value.revision != plan.revision
                    || !checked.value.onlineMembers.equals(ids)
                    || localMembers(plugin,ids) == null
                    || members.values().stream().anyMatch(player ->
                        !allowedSource(plugin, player, destination))
                    || plugin.proxy().getPlayer(leader.getUniqueId()) != leader) {
                for (ReservationSnapshot row : rows) plugin.fail(row,false);
                plugin.releaseGroup(ids);
                plugin.sendKey(leader,"party-changed",
                    "&eA Party ou a conexao de um membro mudou. Tente novamente.");
                return;
            }
            if ("CANCEL_PENDING".equals(plugin.settings().partialTransferPolicy))
                connectSequentially(plugin,leader,destination,rows,members,0);
            else connectIndependently(plugin,leader,destination,rows,members);
        } catch (Exception error) {
            for (ReservationSnapshot row : rows) plugin.fail(row,false);
            plugin.releaseGroup(ids);
            plugin.getLogger().warning("Entrada da Party falhou ("
                + error.getClass().getSimpleName() + ").");
            unavailable(plugin,leader);
        }
    }

    private static void connectSequentially(StarfinMinigamesBungee plugin, ProxiedPlayer leader,
            ServerInfo destination, List<ReservationSnapshot> rows,
            Map<UUID,ProxiedPlayer> members, int index) {
        if (index >= rows.size()) return;
        ReservationSnapshot row = rows.get(index);
        UUID id = row.getPlayerId();
        ProxiedPlayer player = members.get(id);
        if (plugin.proxy().getPlayer(id) != player
                || !allowedSource(plugin, player, destination)) {
            cancelUnattempted(plugin,rows,index);
            notifyPartial(plugin,leader);
            return;
        }
        plugin.trackTransfer(id,row);
        if (destination.equals(player.getServer().getInfo())) {
            if (!plugin.deliverSameServer(player, destination, row)) {
                plugin.clearTransfer(id,row);
                plugin.submitIo(() -> plugin.fail(row,false));
                cancelUnattempted(plugin,rows,index + 1);
                notifyPartial(plugin,leader);
                return;
            }
            plugin.releaseFlight(id);
            plugin.sendKey(player,"connecting","&aEntrando na arena {arena}...",
                "arena",row.getArenaId());
            connectSequentially(plugin,leader,destination,rows,members,index + 1);
            return;
        }
        player.connect(destination,(connected,error) -> {
            if (error != null || !Boolean.TRUE.equals(connected)) {
                plugin.clearTransfer(id,row);
                plugin.submitIo(() -> plugin.fail(row,false));
                cancelUnattempted(plugin,rows,index + 1);
                if (plugin.proxy().getPlayer(id) == player)
                    plugin.sendKey(player,"connection-failed",
                        "&cNao foi possivel conectar ao servidor da partida.");
                notifyPartial(plugin,leader);
                return;
            }
            plugin.releaseFlight(id);
            plugin.sendKey(player,"connecting","&aConectando a arena {arena}...",
                "arena",row.getArenaId());
            connectSequentially(plugin,leader,destination,rows,members,index + 1);
        });
    }

    private static void cancelUnattempted(StarfinMinigamesBungee plugin,
            List<ReservationSnapshot> rows, int fromIndex) {
        plugin.submitIo(() -> {
            for (int i = fromIndex; i < rows.size(); i++)
                plugin.fail(rows.get(i),true);
        });
        for (int i = fromIndex; i < rows.size(); i++)
            plugin.releaseFlight(rows.get(i).getPlayerId());
    }

    private static void connectIndependently(StarfinMinigamesBungee plugin, ProxiedPlayer leader,
            ServerInfo destination, List<ReservationSnapshot> rows,
            Map<UUID,ProxiedPlayer> members) {
        AtomicInteger remaining = new AtomicInteger(rows.size());
        AtomicBoolean failed = new AtomicBoolean();
        for (ReservationSnapshot row : rows) {
            UUID id = row.getPlayerId();
            ProxiedPlayer player = members.get(id);
            if (plugin.proxy().getPlayer(id) != player
                    || !allowedSource(plugin, player, destination)) {
                failed.set(true);
                plugin.releaseFlight(id);
                plugin.submitIo(() -> plugin.fail(row, false));
                if (remaining.decrementAndGet() == 0) notifyPartial(plugin, leader);
                continue;
            }
            plugin.trackTransfer(id,row);
            if (destination.equals(player.getServer().getInfo())) {
                if (plugin.deliverSameServer(player, destination, row)) {
                    plugin.releaseFlight(id);
                    plugin.sendKey(player,"connecting","&aEntrando na arena {arena}...",
                        "arena",row.getArenaId());
                } else {
                    failed.set(true);
                    plugin.clearTransfer(id,row);
                    plugin.submitIo(() -> plugin.fail(row,false));
                }
                if (remaining.decrementAndGet() == 0 && failed.get())
                    notifyPartial(plugin,leader);
                continue;
            }
            player.connect(destination,(connected,error) -> {
                if (error != null || !Boolean.TRUE.equals(connected)) {
                    failed.set(true);
                    plugin.clearTransfer(id,row);
                    plugin.submitIo(() -> plugin.fail(row,false));
                    if (plugin.proxy().getPlayer(id) == player)
                        plugin.sendKey(player,"connection-failed",
                            "&cNao foi possivel conectar ao servidor da partida.");
                } else {
                    plugin.releaseFlight(id);
                    plugin.sendKey(player,"connecting","&aConectando a arena {arena}...",
                        "arena",row.getArenaId());
                }
                if (remaining.decrementAndGet() == 0 && failed.get())
                    notifyPartial(plugin,leader);
            });
        }
    }

    private static void notifyPartial(StarfinMinigamesBungee plugin, ProxiedPlayer leader) {
        if (plugin.proxy().getPlayer(leader.getUniqueId()) == leader)
            plugin.sendKey(leader,"party-transfer-partial",
                "&cUm membro nao conseguiu entrar. A Party permanece formada.");
    }

    private static void unavailable(StarfinMinigamesBungee plugin, ProxiedPlayer leader) {
        plugin.sendKey(leader,"party-unavailable","&cNao foi possivel consultar sua Party.");
    }
}
