package fr.proutlost.director.bootstrap;

import com.google.gson.JsonParser;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Qualification-only profile adapter. Never accepts APPROVED, never interprets
 * the preset numeric values as accepted gameplay calibration. The exact
 * authored resource is SHA-256 bound; unexpected/malformed files fail closed.
 */
public final class DirectorLabObservationProfile {
    private DirectorLabObservationProfile(){}
    private static final String PATH =
        "/data/proutlost_director/director/profiles/default_for_testing.json";
    public static final String ABANDONMENT_OVERRIDE="proutlost.director.labAbandonmentTicks";
    /** Opt-in LAB duration override. The immutable pinned JSON and all other
     * calibration dimensions remain byte-for-byte/value-for-value untouched. */
    public static ConfigView loadRuntime(){
        var base=load();String raw=System.getProperty(ABANDONMENT_OVERRIDE);
        if(raw==null)return base;
        if(!Boolean.getBoolean("proutlost.director.nativeLab"))
            throw new IllegalArgumentException("abandonment override requires nativeLab");
        if(!raw.matches("[1-9][0-9]{0,15}"))
            throw new IllegalArgumentException("canonical positive abandonment ticks required");
        return deriveRuntime(base,Long.parseLong(raw));
    }
    /** Only an exact, currently selected runtime profile may pass J02 integrity. */
    public static boolean isVerifiedRuntime(ConfigView candidate){
        Objects.requireNonNull(candidate);
        var base=load();
        return candidate.equals(base)||candidate.equals(loadRuntime());
    }
    public static ConfigView deriveRuntime(ConfigView base,long ticks){
        if(!Boolean.getBoolean("proutlost.director.nativeLab")||!base.equals(load()))
            throw new IllegalArgumentException("only immutable base LAB profile may be derived");
        long original=base.values().get("pattern.abandonment_enter_ticks").value().longValueExact();
        if(ticks<1||ticks>original)
            throw new IllegalArgumentException("LAB abandonment override must be positive and no slower than base");
        if(ticks==original)return base;
        var values=new HashMap<>(base.values());
        var override=new JsonValue.Number(java.math.BigDecimal.valueOf(ticks));
        values.put("pattern.abandonment_enter_ticks",override);
        var provenance=new JsonValue.ObjectValue(Map.of(
            "schema_version",new JsonValue.Number(java.math.BigDecimal.ONE),
            "kind",new JsonValue.Text("lab_abandonment_override_v1"),
            "base_profile_sha256",new JsonValue.Text(base.provenanceHash().value()),
            "overrides",new JsonValue.ObjectValue(Map.of("pattern.abandonment_enter_ticks",override))));
        return new ConfigView(base.revision().plus(1),
            BlobContent.hash(CanonicalJsonV1.encode(provenance)),Calibration.DEFAULT_FOR_TESTING,values);
    }
    public static ConfigView load() {
        try(InputStream stream=DirectorLabObservationProfile.class.getResourceAsStream(PATH)) {
            if(stream==null)throw new IllegalStateException("missing immutable TEST profile");
            byte[] bytes=stream.readNBytes(1_048_577);
            if(bytes.length>1_048_576)throw new IllegalArgumentException("oversized profile");
            String json=new String(bytes,StandardCharsets.UTF_8);
            var profile=JsonParser.parseString(json).getAsJsonObject();
            if(profile.get("schema_version").getAsInt()!=1 ||
               !profile.get("status").getAsString().equals("DEFAULT_FOR_TESTING") ||
               profile.get("production_approved").getAsBoolean())
                throw new IllegalStateException("only unapproved test profile is valid");
            var object=profile.getAsJsonObject("values");
            if(object==null||object.size()<100)
                throw new IllegalArgumentException("incomplete canonical profile");
            Map<String,JsonValue.Number> values=new HashMap<>();
            for(var entry:object.entrySet()){
                if(!entry.getValue().isJsonPrimitive() ||
                    !entry.getValue().getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("invalid numeric parameter "+entry.getKey());
                values.put(entry.getKey(),new JsonValue.Number(
                    entry.getValue().getAsBigDecimal()));
            }
            for(String critical:List.of("sensor.player_ticks","sensor.camp_ticks",
                "fairness.death_safety_ticks","fairness.transition_safety_ticks",
                "observation.bucket_ticks","observation.max_pending_scopes",
                "spatial.player_cell_blocks","region.camp_radius_blocks",
                "pattern.activity_time_bins","pattern.independent_visit_gap_ticks",
                "budget.snapshot_ops"))
                if(!values.containsKey(critical))
                    throw new IllegalStateException("missing authored sensor bound "+critical);
            var digest=MessageDigest.getInstance("SHA-256").digest(bytes);
            return new ConfigView(new Revision(1),
                new Fingerprint(HexFormat.of().formatHex(digest)),
                Calibration.DEFAULT_FOR_TESTING,values);
        }catch(IOException|NoSuchAlgorithmException error){
            throw new IllegalStateException("unreadable immutable test profile",error);
        }
    }
}
