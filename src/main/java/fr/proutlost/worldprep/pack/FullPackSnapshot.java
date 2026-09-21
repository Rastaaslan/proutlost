package fr.proutlost.worldprep.pack;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record FullPackSnapshot(String minecraftVersion, String neoForgeVersion, List<ModArtifact> mods,
                               List<RegistryFingerprint> registries, List<String> datapackFingerprints,
                               List<String> relevantConfigFingerprints, List<AdapterFingerprint> adapters) {
    public FullPackSnapshot {
        minecraftVersion = required(minecraftVersion); neoForgeVersion = required(neoForgeVersion);
        mods = mods.stream().sorted(Comparator.comparing(ModArtifact::modId)).toList();
        registries = registries.stream().sorted().toList();
        datapackFingerprints = datapackFingerprints.stream().sorted().toList();
        relevantConfigFingerprints = relevantConfigFingerprints.stream().sorted().toList();
        adapters = adapters.stream().sorted().toList();
    }
    public void requireClassified() {
        mods.stream().filter(m -> m.classification() == ContentClassification.UNCLASSIFIED).findFirst()
                .ifPresent(m -> { throw new IllegalStateException("Unclassified installed mod: " + m.modId()); });
    }
    public String fingerprint() { var digest = CanonicalDigest.sha256(); toCanonicalLines().forEach(digest::putString); return digest.finish(); }
    List<String> toCanonicalLines() {
        var lines = new java.util.ArrayList<String>(); lines.add("minecraft=" + minecraftVersion); lines.add("neoforge=" + neoForgeVersion);
        mods.forEach(m -> lines.add("mod=" + m.modId()+"|"+m.version()+"|"+m.jarSha256()+"|"+m.classification()+"|"+m.capabilities()));
        registries.forEach(r -> lines.add("registry="+r.registryId()+"|"+r.canonicalSha256()));
        datapackFingerprints.forEach(x -> lines.add("datapack="+x)); relevantConfigFingerprints.forEach(x -> lines.add("config="+x));
        adapters.forEach(a -> lines.add("adapter="+a.adapterId()+"|"+a.version()+"|"+a.sha256())); return List.copyOf(lines);
    }
    private static String required(String s) { if (s == null || s.isBlank()) throw new IllegalArgumentException("Version required"); return s; }
}
