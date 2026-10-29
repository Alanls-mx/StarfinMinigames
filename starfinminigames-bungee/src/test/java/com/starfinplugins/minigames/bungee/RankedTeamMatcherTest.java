package com.starfinplugins.minigames.bungee;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.config.Configuration;
import org.junit.Test;
import static org.junit.Assert.*;

public class RankedTeamMatcherTest {
    private static RankedMatchmakingSettings settings() {
        return new RankedMatchmakingSettings(new Configuration());
    }
    private static RankedTeamMatcher.Entry entry(int rating, int size, long joined) {
        return new RankedTeamMatcher.Entry(UUID.randomUUID(), rating, size, joined);
    }
    private static Map<String, Integer> teams(int size) {
        Map<String, Integer> teams = new LinkedHashMap<String, Integer>();
        teams.put("red", size); teams.put("blue", size); return teams;
    }
    @Test public void equalSoloRatingsFillDistinctTeams() {
        long now = System.currentTimeMillis();
        RankedTeamMatcher.Entry a = entry(1000, 1, now - 1000);
        RankedTeamMatcher.Entry b = entry(1000, 1, now - 900);
        Map<UUID, String> result = RankedTeamMatcher.assign(Arrays.asList(a, b), teams(1), settings(), now);
        assertEquals(2, result.size());
        assertNotEquals(result.get(a.id), result.get(b.id));
    }
    @Test public void distantRatingsWaitUntilBothRangesExpand() {
        long now = System.currentTimeMillis();
        Configuration config = new Configuration();
        config.set("ranked-matchmaking.teams.balance-enabled", false);
        RankedMatchmakingSettings wideTeams = new RankedMatchmakingSettings(config);
        RankedTeamMatcher.Entry a = entry(1000, 1, now - 1000);
        RankedTeamMatcher.Entry b = entry(1400, 1, now - 1000);
        assertTrue(RankedTeamMatcher.assign(Arrays.asList(a, b), teams(1), wideTeams, now).isEmpty());
        assertEquals(2, RankedTeamMatcher.assign(Arrays.asList(
            entry(1000, 1, now - 100000), entry(1400, 1, now - 100000)),
            teams(1), wideTeams, now).size());
    }
    @Test public void partiesStayTogetherAndBalanceIsRequired() {
        long now = System.currentTimeMillis();
        RankedTeamMatcher.Entry duo = entry(1100, 2, now - 1000);
        RankedTeamMatcher.Entry soloA = entry(1050, 1, now - 900);
        RankedTeamMatcher.Entry soloB = entry(1150, 1, now - 800);
        Map<UUID, String> result = RankedTeamMatcher.assign(
            Arrays.asList(duo, soloA, soloB), teams(2), settings(), now);
        assertEquals(3, result.size());
        assertEquals(result.get(soloA.id), result.get(soloB.id));
        assertNotEquals(result.get(duo.id), result.get(soloA.id));
    }
    @Test public void oversizedPartyCannotBeSplit() {
        long now = System.currentTimeMillis();
        assertTrue(RankedTeamMatcher.assign(Arrays.asList(entry(1000, 3, now - 1000),
            entry(1000, 1, now - 900)), teams(2), settings(), now).isEmpty());
    }
    @Test public void rangeStopsAtMaximum() {
        RankedMatchmakingSettings rules = settings();
        long now = System.currentTimeMillis();
        assertEquals(250, rules.range(now, now));
        assertEquals(325, rules.range(now - 30000, now));
        assertEquals(600, rules.range(now - 600000, now));
    }
    @Test public void trioPartyAndSoloFillOneTeamAgainstAnotherTrio() {
        long now = System.currentTimeMillis();
        RankedTeamMatcher.Entry pair = entry(1060, 2, now - 3000);
        RankedTeamMatcher.Entry solo = entry(1040, 1, now - 2000);
        RankedTeamMatcher.Entry trio = entry(1050, 3, now - 1000);
        Map<UUID, String> result = RankedTeamMatcher.assign(
            Arrays.asList(pair, solo, trio), teams(3), settings(), now);
        assertEquals(3, result.size());
        assertEquals(result.get(pair.id), result.get(solo.id));
        assertNotEquals(result.get(pair.id), result.get(trio.id));
    }
    @Test public void fourPlayerPartyAndFourSoloPlayersRemainWhole() {
        long now = System.currentTimeMillis();
        RankedTeamMatcher.Entry party = entry(1000, 4, now - 5000);
        RankedTeamMatcher.Entry a = entry(990, 1, now - 4000);
        RankedTeamMatcher.Entry b = entry(1010, 1, now - 3000);
        RankedTeamMatcher.Entry c = entry(995, 1, now - 2000);
        RankedTeamMatcher.Entry d = entry(1005, 1, now - 1000);
        Map<UUID, String> result = RankedTeamMatcher.assign(
            Arrays.asList(party, a, b, c, d), teams(4), settings(), now);
        assertEquals(5, result.size());
        for (RankedTeamMatcher.Entry solo : Arrays.asList(a, b, c, d))
            assertNotEquals(result.get(party.id), result.get(solo.id));
    }
    @Test public void threeTeamArenaRequiresEveryTeamFilled() {
        long now = System.currentTimeMillis();
        Map<String, Integer> capacities = teams(1); capacities.put("green", 1);
        RankedTeamMatcher.Entry a = entry(1000, 1, now - 3000);
        RankedTeamMatcher.Entry b = entry(1000, 1, now - 2000);
        assertTrue(RankedTeamMatcher.assign(Arrays.asList(a, b), capacities, settings(), now).isEmpty());
        RankedTeamMatcher.Entry c = entry(1000, 1, now - 1000);
        assertEquals(3, RankedTeamMatcher.assign(Arrays.asList(a, b, c),
            capacities, settings(), now).size());
    }
    @Test public void narrowWaiterCannotFaceDistantOpponentEvenWhenAnchorIsOld() {
        long now = System.currentTimeMillis();
        Configuration config = new Configuration();
        config.set("ranked-matchmaking.teams.balance-enabled", false);
        RankedMatchmakingSettings rules = new RankedMatchmakingSettings(config);
        RankedTeamMatcher.Entry old = entry(1000, 1, now - 180000);
        RankedTeamMatcher.Entry fresh = entry(1400, 1, now - 1000);
        assertTrue(RankedTeamMatcher.assign(Arrays.asList(old, fresh), teams(1), rules, now).isEmpty());
    }
    @Test public void outlierAtFrontDoesNotStarveCompatiblePair() {
        long now = System.currentTimeMillis();
        RankedTeamMatcher.Entry old = entry(2000, 1, now - 3000);
        RankedTeamMatcher.Entry a = entry(1000, 1, now - 2000);
        RankedTeamMatcher.Entry b = entry(1010, 1, now - 1000);
        Map<UUID, String> result = RankedTeamMatcher.assign(
            Arrays.asList(old, a, b), teams(1), settings(), now);
        assertEquals(2, result.size());
        assertFalse(result.containsKey(old.id));
        assertTrue(result.containsKey(a.id)); assertTrue(result.containsKey(b.id));
    }
}
