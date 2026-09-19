package fr.proutlost.worldprep.pack;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.util.List;

/** The identity subset capable of changing a WorldPrep semantic result. */
public record EnvironmentalSemanticSnapshot(List<ModArtifact> mods, List<RegistryFingerprint> registries,
        List<String> datapacks, List<String> configs, List<AdapterFingerprint> adapters) {
    public static EnvironmentalSemanticSnapshot from(FullPackSnapshot full) {
        full.requireClassified();
        return new EnvironmentalSemanticSnapshot(full.mods().stream().filter(m -> m.classification().affectsEnvironment()).toList(),
                full.registries(), full.datapackFingerprints(), full.relevantConfigFingerprints(), full.adapters());
    }
    public EnvironmentalSemanticSnapshot {
        mods = mods.stream().sorted(java.util.Comparator.comparing(ModArtifact::modId)).toList();
        registries = registries.stream().sorted().toList(); datapacks = datapacks.stream().sorted().toList();
        configs = configs.stream().sorted().toList(); adapters = adapters.stream().sorted().toList();
    }
    public String fingerprint() {
        var digest = CanonicalDigest.sha256(); mods.forEach(m -> digest.putString(m.toString()));
        registries.forEach(r -> digest.putString(r.toString())); datapacks.forEach(digest::putString);
        configs.forEach(digest::putString); adapters.forEach(a -> digest.putString(a.toString())); return digest.finish();
    }
}
