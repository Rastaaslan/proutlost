package fr.proutlost.p008qa.cohort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure validation of observations supplied by the platform's actual PlayerList.
 * Never constructs players, identities, capabilities or synthetic membership. */
public final class CohortPlayerCensus {
    private CohortPlayerCensus() {}
    public record Observed(UUID id, boolean fake) {}
    public record Result(int players, String observedUuidSha256) {}
    public static int count(Set<UUID> allowed, List<Observed> actual) { return validatedIds(allowed, actual).size(); }
    public static Result inspect(Set<UUID> allowed, List<Observed> actual) {
        var ids = validatedIds(allowed, actual); return new Result(ids.size(), digest(ids));
    }
    private static Set<UUID> validatedIds(Set<UUID> allowed, List<Observed> actual) {
        require(allowed != null && !allowed.isEmpty() && allowed.size() <= 25 && allowed.stream().noneMatch(Objects::isNull), "bounded planned connection whitelist");
        require(actual != null && actual.size() <= 25, "actual PlayerList bound");
        var ids = new HashSet<UUID>();
        for (var player : actual) {
            require(player != null && player.id() != null && !player.fake(), "actual non-FakePlayer observation required");
            require(allowed.contains(player.id()) && ids.add(player.id()), "unplanned or duplicate actual connection identity");
        }
        return ids;
    }
    public static String digest(Set<UUID> ids) {
        require(ids != null && ids.size() <= 25 && ids.stream().noneMatch(Objects::isNull), "bounded UUID set");
        String body = String.join("\n", ids.stream().map(UUID::toString).sorted().toList()) + "\n";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean good, String why) { if (!good) throw new IllegalArgumentException(why); }
}
