package fr.proutlost.worldprep.pack;

import fr.proutlost.worldprep.plan.CanonicalDigest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Captures the actual installed artifacts, selected datapacks, relevant configs, and live registries. */
public final class EnvironmentalPackInspector {
    public FullPackSnapshot inspect(ServerLevel level, Map<String, ContentClassification> classifications,
            List<String> relevantConfigNames, List<AdapterFingerprint> adapters) throws IOException {
        var mods = new ArrayList<ModArtifact>();
        for (var info : ModList.get().getMods()) {
            String id = info.getModId();
            Path artifact = info.getOwningFile().getFile().getFilePath();
            String hash = Files.isDirectory(artifact) ? hashTree(artifact) : hashFile(artifact);
            mods.add(new ModArtifact(id, info.getVersion().toString(), hash,
                    classifications.getOrDefault(id, ContentClassification.UNCLASSIFIED), EnumSet.noneOf(ContentCapability.class)));
        }
        var registries = List.of(
                registry(level, Registries.BIOME), registry(level, Registries.BLOCK),
                registry(level, Registries.STRUCTURE), registry(level, Registries.STRUCTURE_SET),
                registry(level, Registries.TEMPLATE_POOL), registry(level, Registries.CONFIGURED_FEATURE),
                registry(level, Registries.PLACED_FEATURE), registry(level, Registries.ENTITY_TYPE));
        var datapacks = level.getServer().getPackRepository().getSelectedIds().stream().sorted().toList();
        var configs = new ArrayList<String>();
        for (String name : relevantConfigNames.stream().sorted().toList()) {
            if (name.contains("..") || Path.of(name).isAbsolute()) throw new IllegalArgumentException("Unsafe config name");
            Path path = FMLPaths.CONFIGDIR.get().resolve(name).normalize();
            if (!path.startsWith(FMLPaths.CONFIGDIR.get().normalize()) || !Files.isRegularFile(path)) {
                throw new IOException("Missing relevant config " + name);
            }
            configs.add(name + "=" + hashFile(path));
        }
        return new FullPackSnapshot("1.21.1", "21.1.248", mods, registries, datapacks, configs, adapters);
    }

    private static RegistryFingerprint registry(ServerLevel level, ResourceKey<? extends Registry<?>> key) {
        Registry<?> registry = level.registryAccess().registryOrThrow(key);
        var digest = CanonicalDigest.sha256().putString(key.location().toString());
        registry.keySet().stream().map(Object::toString).sorted().forEach(digest::putString);
        return new RegistryFingerprint(key.location().toString(), digest.finish());
    }

    private static String hashTree(Path root) throws IOException {
        var digest = CanonicalDigest.sha256();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.putString(root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/"));
                digest.putString(hashFile(file));
            }
        }
        return digest.finish();
    }

    private static String hashFile(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
