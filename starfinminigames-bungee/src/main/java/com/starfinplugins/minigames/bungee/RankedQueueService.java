package com.starfinplugins.minigames.bungee;

import com.starfinplugins.minigames.api.ReservationSnapshot;
import com.starfinplugins.social.api.PartyResult;
import com.starfinplugins.social.api.PartyService;
import com.starfinplugins.social.api.PartyTransferPlan;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** Party-aware proxy façade; the database owns queue and allocation state. */
final class RankedQueueService {
    private final StarfinMinigamesBungee plugin;
    private final UUID proxyId;
    private final Set<UUID> enrolling = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    RankedQueueService(StarfinMinigamesBungee plugin, UUID proxyId) {
        this.plugin = plugin; this.proxyId = proxyId;
    }
    private RankedQueueStore store() {
        BungeeDatabase db = plugin.database();
        return db == null ? null : new RankedQueueStore(db, plugin.settings(), proxyId);
    }
    boolean available() {
        return plugin.settings().networkEnabled && plugin.settings().ranked.enabled
            && plugin.database() != null;
    }
    void join(ProxiedPlayer player, String rawMode) {
        String mode = canonical(rawMode);
        if (!available() || mode == null || !plugin.settings().ranked.modes.contains(mode)) {
            plugin.sendKey(player, "ranked-unavailable", "&cAs ranqueadas estao temporariamente indisponiveis.");
            return;
        }
        if (!inLobby(player) || !enrolling.add(player.getUniqueId())) {
            plugin.send(player, "&eEntre no lobby e finalize a busca atual antes de iniciar outra.");
            return;
        }
        PartyService social = PartyJoinBridge.service(plugin);
        if (plugin.proxy().getPluginManager().getPlugin("StarfinSocial") != null && social == null) {
            enrolling.remove(player.getUniqueId());
            plugin.send(player, "&cParty indisponivel. Tente novamente em instantes.");
            return;
        }
        if (social == null) { enroll(player, mode, null, 0L,
            Collections.singletonList(player.getUniqueId()), null); return; }
        social.getParty(player.getUniqueId()).whenComplete((party, error) -> {
            if (plugin.proxy().getPlayer(player.getUniqueId()) != player) {
                enrolling.remove(player.getUniqueId()); return;
            }
            if (error != null) { enrolling.remove(player.getUniqueId()); unavailable(player); return; }
            if (party == null) {
                enroll(player, mode, null, 0L, Collections.singletonList(player.getUniqueId()), null);
                return;
            }
            social.planTransfer(player.getUniqueId(), "bedwars", 64).whenComplete((result, failure) -> {
                if (failure != null || result == null || !result.succeeded() || result.value == null) {
                    enrolling.remove(player.getUniqueId());
                    plugin.send(player, "&eApenas o lider de uma Party valida pode iniciar a fila.");
                    return;
                }
                PartyTransferPlan plan = result.value;
                if (!party.id.equals(plan.partyId) || plan.onlineMembers.isEmpty()
                        || !plan.onlineMembers.contains(player.getUniqueId())) {
                    enrolling.remove(player.getUniqueId()); unavailable(player); return;
                }
                int max = capacity(mode);
                if (plan.onlineMembers.size() > max
                        || ("solo".equals(mode) && plan.onlineMembers.size() > 1)) {
                    enrolling.remove(player.getUniqueId());
                    plugin.send(player, "&cO tamanho da Party nao e permitido nesta modalidade.");
                    return;
                }
                for (UUID id : plan.onlineMembers) {
                    ProxiedPlayer member = plugin.proxy().getPlayer(id);
                    if (!inLobby(member)) {
                        enrolling.remove(player.getUniqueId());
                        plugin.send(player, "&eTodos os membros precisam estar neste proxy e no lobby.");
                        return;
                    }
                }
                enroll(player, mode, plan.partyId, plan.revision,
                    new ArrayList<UUID>(plan.onlineMembers), social);
            });
        });
    }
    private void enroll(ProxiedPlayer leader, String mode, UUID party, long revision,
                        List<UUID> members, PartyService social) {
        if (!plugin.submitIo(() -> {
            try {
                RankedQueueStore store = store();
                if (store == null) throw new SQLException("Banco indisponivel");
                if (social != null && party != null && !sameParty(social, leader.getUniqueId(),
                        party, revision, members)) throw new IllegalStateException("Party alterada");
                RankedQueueStore.Policy policy = store.policyFor(mode);
                if (policy == null) throw new SQLException("Politica competitiva indisponivel");
                Map<UUID, Integer> ratings = store.ratings(members, mode, policy);
                int low = Collections.min(ratings.values());
                int high = Collections.max(ratings.values());
                if (high - low > plugin.settings().ranked.maximumPartyDifference) {
                    plugin.send(leader, "&cA diferenca de MMR da Party excede o limite permitido.");
                    return;
                }
                if (!allInLobby(members)) throw new IllegalStateException("Membro saiu do lobby");
                store.enqueue(members, leader.getUniqueId(), party, revision, mode, policy, ratings);
                for (UUID id : members) {
                    ProxiedPlayer target = plugin.proxy().getPlayer(id);
                    if (target != null) plugin.sendKey(target, "ranked-joined",
                        "&aVoce entrou na fila ranqueada {mode}.", "mode", mode);
                }
                tick();
            } catch (RankedQueueStore.Suspended suspended) {
                long minutes = Math.max(1L, (suspended.untilAt - System.currentTimeMillis() + 59999L) / 60000L);
                plugin.sendKey(leader, "ranked-suspended",
                    "&cEntrada ranqueada suspensa por mais {minutes} minuto(s).",
                    "minutes", Long.toString(minutes));
            } catch (SQLException | RuntimeException error) {
                RankedQueueStore store = store();
                try {
                    if (store != null && store.status(leader.getUniqueId()) != null)
                        plugin.send(leader, "&eVoce ja possui uma busca ranqueada ativa.");
                    else unavailable(leader);
                } catch (SQLException ignored) { unavailable(leader); }
                plugin.getLogger().warning("Entrada ranqueada recusada ("
                    + error.getClass().getSimpleName() + ").");
            } finally { enrolling.remove(leader.getUniqueId()); }
        })) {
            enrolling.remove(leader.getUniqueId()); unavailable(leader);
        }
    }
    void leave(ProxiedPlayer player) {
        if (!plugin.submitIo(() -> {
            RankedQueueStore store = store();
            if (store == null) { unavailable(player); return; }
            try {
                RankedQueueStore.Status status = store.status(player.getUniqueId());
                if (status == null) { plugin.send(player, "&eVoce nao esta na fila."); return; }
                if ("WAITING".equals(status.state)) {
                    if (store.cancel(player.getUniqueId(), proxyId))
                        plugin.sendKey(player, "ranked-left", "&eVoce saiu da fila ranqueada.");
                    else plugin.send(player, "&eA busca mudou de estado. Consulte /smranked status.");
                } else if (status.allocationId != null && !"ADMITTED".equals(status.state)) {
                    store.failAllocation(status.allocationId);
                    plugin.send(player, "&eFormacao cancelada antes da partida; sem penalidade.");
                } else plugin.send(player, "&eA partida ja iniciou. Use os comandos do BedWars.");
            } catch (SQLException error) { unavailable(player); }
        })) unavailable(player);
    }
    void status(ProxiedPlayer player) {
        if (!plugin.submitIo(() -> {
            try {
                RankedQueueStore store = store();
                if (store == null) throw new SQLException("Banco indisponivel");
                RankedQueueStore.Status s = store.status(player.getUniqueId());
                if (s == null) { plugin.send(player, "&eVoce nao esta na fila ranqueada."); return; }
                long seconds = Math.max(0L, (System.currentTimeMillis() - s.joinedAt) / 1000L);
                int range = plugin.settings().ranked.range(s.joinedAt, System.currentTimeMillis());
                plugin.send(player, "&b" + s.mode + " &7| " + s.state + " &7| " + seconds
                    + "s &7| Party: " + s.size + " &7| MMR: " + s.mmr + " ± " + range);
            } catch (SQLException error) { unavailable(player); }
        })) unavailable(player);
    }
    void adminStatus(net.md_5.bungee.api.CommandSender sender, String topic, String target) {
        if (!plugin.submitIo(() -> {
            try {
                RankedQueueStore store = store();
                if (store == null) { plugin.send(sender, "&cBanco ranqueado indisponivel."); return; }
                if ("status".equals(topic)) {
                    plugin.send(sender, "&bRanqueadas: " + (available() ? "ativas" : "desativadas")
                        + " &7| inscricoes esperando: " + store.waitingCount());
                    return;
                }
                for (String line : store.diagnostics(topic, target)) plugin.send(sender, "&7" + line);
            } catch (SQLException error) { plugin.send(sender, "&cBanco ranqueado indisponivel."); }
        })) plugin.send(sender, "&cProxy ocupado.");
    }
    void reload(net.md_5.bungee.api.CommandSender sender) {
        plugin.send(sender, plugin.reloadSafeSettings());
    }
    void tick() {
        if (!available()) return;
        Set<UUID> local = new HashSet<UUID>();
        for (ProxiedPlayer player : plugin.proxy().getPlayers())
            if (inLobby(player)) local.add(player.getUniqueId());
        plugin.submitIo(() -> {
            try {
                RankedQueueStore store = store();
                if (store == null) return;
                store.heartbeatOwned(local);
                PartyService social = PartyJoinBridge.service(plugin);
                for (RankedQueueStore.OwnedParty party : store.ownedParties()) {
                    if (social != null && sameParty(social, party.leader,
                            party.partyId, party.revision, party.members)) continue;
                    store.cancel(party.leader, proxyId);
                    ProxiedPlayer leader = plugin.proxy().getPlayer(party.leader);
                    if (leader != null) plugin.sendKey(leader, "ranked-party-changed",
                        "&eSua Party mudou; a busca ranqueada foi cancelada.");
                }
                store.expire();
                for (String mode : plugin.settings().ranked.modes) store.allocate(mode);
                for (RankedQueueStore.Status ready : store.ownedReady()) {
                    List<ReservationSnapshot> rows = store.claimTransfers(ready);
                    if (!rows.isEmpty()) transfer(store, rows);
                }
            } catch (SQLException | RuntimeException error) {
                plugin.getLogger().warning("Ciclo ranqueado pendente ("
                    + error.getClass().getSimpleName() + ").");
            }
        });
    }
    private void transfer(RankedQueueStore store, List<ReservationSnapshot> rows) {
        ReservationSnapshot first = rows.get(0);
        UUID allocation = first.getRankedAllocationId();
        String name = plugin.settings().serverNames.get(first.getServerId());
        ServerInfo destination = name == null ? null : plugin.proxy().getServerInfo(name);
        if (destination == null || !allInLobby(ids(rows))) { fail(store, allocation); return; }
        UUID partyId = first.getPartyId();
        if (partyId != null) {
            PartyService social = PartyJoinBridge.service(plugin);
            if (social == null || !sameParty(social, first.getLeaderId(), partyId,
                    first.getPartyRevision(), ids(rows))) {
                fail(store, allocation); return;
            }
        }
        for (ReservationSnapshot row : rows) {
            ProxiedPlayer player = plugin.proxy().getPlayer(row.getPlayerId());
            if (!inLobby(player)) { fail(store, allocation); return; }
        }
        for (ReservationSnapshot row : rows) {
            ProxiedPlayer player = plugin.proxy().getPlayer(row.getPlayerId());
            plugin.trackTransfer(row.getPlayerId(), row);
            plugin.sendKey(player, "ranked-found", "&aPartida encontrada! Preparando sua equipe...");
            if (player.getServer() != null && destination.equals(player.getServer().getInfo())) {
                if (!plugin.deliverSameServer(player, destination, row)) {
                    plugin.clearTransfer(row.getPlayerId(), row);
                    fail(store, allocation);
                    plugin.send(player, "&cFalha ao entregar a reserva. Formacao cancelada.");
                    return;
                }
                continue;
            }
            player.connect(destination, (connected, error) -> {
                plugin.releaseFlight(row.getPlayerId());
                if (error != null || !Boolean.TRUE.equals(connected)) {
                    plugin.clearTransfer(row.getPlayerId(), row);
                    plugin.submitIo(() -> fail(store, allocation));
                    if (plugin.proxy().getPlayer(row.getPlayerId()) == player)
                        plugin.send(player, "&cFalha ao conectar. Formacao cancelada sem penalidade.");
                }
            });
        }
    }
    private void fail(RankedQueueStore store, UUID allocation) {
        try { store.failAllocation(allocation); }
        catch (SQLException error) {
            plugin.getLogger().warning("Falha ao cancelar alocacao " + allocation + "; lease expirara.");
        }
    }
    private boolean sameParty(PartyService social, UUID leader, UUID party,
                              long revision, List<UUID> members) {
        try {
            PartyResult<PartyTransferPlan> answer = social.planTransfer(leader, "bedwars", 64)
                .get(5, TimeUnit.SECONDS);
            return answer != null && answer.succeeded() && answer.value != null
                && party.equals(answer.value.partyId) && revision == answer.value.revision
                && new HashSet<UUID>(members).equals(new HashSet<UUID>(answer.value.onlineMembers));
        } catch (Exception error) { return false; }
    }
    private static List<UUID> ids(List<ReservationSnapshot> rows) {
        List<UUID> ids = new ArrayList<UUID>();
        for (ReservationSnapshot row : rows) ids.add(row.getPlayerId());
        return ids;
    }
    private boolean allInLobby(List<UUID> ids) {
        for (UUID id : ids) if (!inLobby(plugin.proxy().getPlayer(id))) return false;
        return true;
    }
    private boolean inLobby(ProxiedPlayer player) {
        return player != null && player.getServer() != null
            && plugin.settings().lobbyServer.equals(player.getServer().getInfo().getName())
            && authenticated(player);
    }
    private boolean authenticated(ProxiedPlayer player) {
        if (!plugin.settings().ranked.requireStarfinLogin) return true;
        net.md_5.bungee.api.plugin.Plugin login =
            plugin.proxy().getPluginManager().getPlugin("StarfinLogin");
        if (login == null) return false;
        try {
            return Boolean.TRUE.equals(login.getClass().getMethod("isAuthenticated", UUID.class)
                .invoke(login, player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
    private static int capacity(String mode) {
        switch (mode) {
            case "solo": return 1; case "duo": return 2; case "trio": return 3;
            case "squad": return 4; default: return 64;
        }
    }
    static String canonical(String text) {
        if (text == null) return null;
        String mode = text.toLowerCase(java.util.Locale.ROOT);
        if (Arrays.asList("solo", "single").contains(mode)) return "solo";
        if (Arrays.asList("duo", "dupla").contains(mode)) return "duo";
        if (Arrays.asList("trio", "tripla").contains(mode)) return "trio";
        if (Arrays.asList("squad", "quarteto").contains(mode)) return "squad";
        if ("custom".equals(mode)) return "custom";
        return null;
    }
    private void unavailable(ProxiedPlayer player) {
        plugin.sendKey(player, "ranked-unavailable", "&cAs ranqueadas estao temporariamente indisponiveis.");
    }
}
