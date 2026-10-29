package com.starfinplugins.minigames.bungee;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, pure team search. Entries are indivisible, including Parties. */
final class RankedTeamMatcher {
    private RankedTeamMatcher() { }

    static final class Entry {
        final UUID id;
        final int rating, size;
        final long joinedAt;
        Entry(UUID id, int rating, int size, long joinedAt) {
            this.id = id; this.rating = rating; this.size = size; this.joinedAt = joinedAt;
        }
    }

    static Map<UUID, String> assign(List<Entry> entries, Map<String, Integer> capacities,
                                     RankedMatchmakingSettings settings, long now) {
        if (entries.isEmpty() || capacities.size() < 2) return Collections.emptyMap();
        List<Entry> sorted = new ArrayList<Entry>(entries);
        sorted.sort(Comparator.comparingLong((Entry e) -> e.joinedAt)
            .thenComparing(e -> e.id.toString()));
        // An unmatched oldest entry must not block compatible players behind it.
        for (int offset = 0; offset < Math.min(8, sorted.size()); offset++) {
            Map<UUID, String> result = assignFrom(sorted.subList(offset, sorted.size()),
                capacities, settings, now);
            if (!result.isEmpty()) return result;
        }
        return Collections.emptyMap();
    }

    private static Map<UUID, String> assignFrom(List<Entry> sorted,
                                                 Map<String, Integer> capacities,
                                                 RankedMatchmakingSettings settings, long now) {
        Entry anchor = sorted.get(0);
        List<Entry> compatible = new ArrayList<Entry>();
        for (Entry candidate : sorted) {
            if (Math.abs(candidate.rating - anchor.rating) <= settings.range(candidate.joinedAt, now)
                    && Math.abs(candidate.rating - anchor.rating) <= settings.range(anchor.joinedAt, now))
                compatible.add(candidate);
        }
        if (compatible.size() > 24) compatible = new ArrayList<Entry>(compatible.subList(0, 24));
        List<String> teams = new ArrayList<String>(capacities.keySet());
        int[] capacity = new int[teams.size()], used = new int[teams.size()];
        long[] sums = new long[teams.size()];
        int total = 0;
        for (int i = 0; i < teams.size(); i++) {
            capacity[i] = capacities.get(teams.get(i));
            if (capacity[i] < 1) return Collections.emptyMap();
            total += capacity[i];
        }
        int available = 0;
        for (Entry entry : compatible) available += entry.size;
        if (total < 2 || available < total || anchor.size > total) return Collections.emptyMap();
        Search search = new Search(compatible, teams, capacity, used, sums,
            settings.balance ? settings.maximumTeamDifference : Integer.MAX_VALUE,
            total, settings, now);
        search.visit(0, 0, new LinkedHashMap<UUID, String>());
        return search.best == null ? Collections.<UUID, String>emptyMap()
            : Collections.unmodifiableMap(search.best);
    }

    private static final class Search {
        final List<Entry> entries;
        final List<String> teams;
        final int[] capacity, used;
        final long[] sums;
        final int maxDifference, total;
        final RankedMatchmakingSettings settings;
        final long now;
        int examined;
        double bestSpread = Double.MAX_VALUE;
        Map<UUID, String> best;

        Search(List<Entry> entries, List<String> teams, int[] capacity, int[] used,
               long[] sums, int maxDifference, int total,
               RankedMatchmakingSettings settings, long now) {
            this.entries = entries; this.teams = teams; this.capacity = capacity;
            this.used = used; this.sums = sums;
            this.maxDifference = maxDifference; this.total = total;
            this.settings = settings; this.now = now;
        }
        void visit(int index, int filled, Map<UUID, String> assignment) {
            if (++examined > 200000) return;
            if (filled == total) {
                for (int i = 0; i < entries.size(); i++) {
                    Entry a = entries.get(i);
                    if (!assignment.containsKey(a.id)) continue;
                    for (int j = i + 1; j < entries.size(); j++) {
                        Entry b = entries.get(j);
                        if (!assignment.containsKey(b.id)
                                || assignment.get(a.id).equals(assignment.get(b.id))) continue;
                        int distance = Math.abs(a.rating - b.rating);
                        if (distance > settings.range(a.joinedAt, now)
                                || distance > settings.range(b.joinedAt, now)) return;
                    }
                }
                double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
                for (int i = 0; i < teams.size(); i++) {
                    if (used[i] != capacity[i]) return;
                    double mean = (double) sums[i] / used[i];
                    low = Math.min(low, mean); high = Math.max(high, mean);
                }
                double spread = high - low;
                if (spread <= maxDifference && spread < bestSpread) {
                    bestSpread = spread; best = new LinkedHashMap<UUID, String>(assignment);
                }
                return;
            }
            if (index >= entries.size()) return;
            int remaining = 0;
            for (int i = index; i < entries.size(); i++) remaining += entries.get(i).size;
            if (remaining < total - filled) return;
            Entry entry = entries.get(index);
            for (int i = 0; i < teams.size(); i++) {
                if (used[i] + entry.size > capacity[i]) continue;
                used[i] += entry.size; sums[i] += (long) entry.rating * entry.size;
                assignment.put(entry.id, teams.get(i));
                visit(index + 1, filled + entry.size, assignment);
                assignment.remove(entry.id); sums[i] -= (long) entry.rating * entry.size;
                used[i] -= entry.size;
            }
            if (index > 0) visit(index + 1, filled, assignment);
        }
    }
}
