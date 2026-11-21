package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Explicit Contra-only V5 physical scheduler identity; NOT a certification.
 *
 * V5 has one world-global immutable qualification request and one cold A/B
 * backup identity. Instead of weakening the 64-group batch bound, derive 17
 * individually bounded, non-overlapping physical children which collectively
 * cover all 1024 groups. A future native coordinator must prove every child's
 * real structure and species receipts AND both frozen backups before certifying.
 *
 * This class only creates/verifies deterministic immutable child identities.
 * It does NOT admit a receipt, create/modify a world or bypass any live guard.
 */
public final class ContraV5SegmentedManifest {
    public static final int VERSION = 1;
    public static final String DIMENSION = "minecraft:overworld";
    public static final int EXACT_GROUPS = 1024;
    public static final int EXACT_CHUNKS = 64516;
    public static final int MAX_GROUPS_PER_CHILD = 64;
    public record Child(int ordinal, String areaId, WorldPrepSavedData.Area area,
                        UUID structureBatch, Map<String,UUID> faunaBatches,
                        int groups, int chunks) {
        public Child { faunaBatches=Map.copyOf(faunaBatches); }
    }
    public record Manifest(int version, UUID qualification, UUID ecology,
                           String frozenRequestSha256, String partitionSha256,
                           List<String> species, List<Child> children,
                           int totalGroups, int totalChunks) {
        public Manifest {
            species=List.copyOf(species);
            children=List.copyOf(children);
        }
    }
    private static UUID child(UUID q,UUID parent,String digest,String label,String extra) {
        return UUID.nameUUIDFromBytes(("proutlost:contra:v5:segmented:"
                +ContraV5SectorLineage.PLAN_SHA256+":"+q+":"+parent+":"+digest
                +":"+label+":"+extra).getBytes(StandardCharsets.UTF_8));
    }

    /** Require explicit pre-frozen SHA, parent UUID and species identities. */
    public static Manifest create(UUID qualification,UUID ecology,
                                  String frozenRequestSha256,List<String> species) {
        Objects.requireNonNull(qualification);Objects.requireNonNull(ecology);
        if(qualification.equals(ecology)
            ||frozenRequestSha256==null||!frozenRequestSha256.matches("[0-9a-f]{64}")
            ||species==null||species.size()<2||species.size()>8
            ||species.stream().anyMatch(s->s==null||!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            ||new HashSet<>(species).size()!=species.size())
            throw new IllegalArgumentException("Exact frozen V5 identity / distinct 2..8 species required");
        // Canonical species ordering is explicit; no unrequested reordering.
        var children=new ArrayList<Child>();
        var seen=new HashSet<UUID>();int groupSum=0,chunkSum=0;
        for(int ordinal=0;ordinal<ContraV5SectorLineage.SECTOR_COUNT;ordinal++) {
            var bounds=ContraV5SectorLineage.sectorBounds(ordinal);
            String id=ContraV5SectorLineage.sectorId(ordinal);
            var area=new WorldPrepSavedData.Area(id,DIMENSION,
                bounds.minX(),bounds.minZ(),bounds.maxX(),bounds.maxZ());
            UUID structure=child(qualification,ecology,frozenRequestSha256,id,"structures");
            if(!seen.add(structure))throw new IllegalArgumentException("Duplicate structure child UUID");
            var fauna=new LinkedHashMap<String,UUID>();
            for(String animal:species) {
                UUID uuid=child(qualification,ecology,frozenRequestSha256,id,"fauna/"+animal);
                if(!seen.add(uuid))throw new IllegalArgumentException("Duplicate fauna child UUID");
                fauna.put(animal,uuid);
            }
            var groups=new PerimeterGroups(structure,frozenRequestSha256,DIMENSION,
                area.minX(),area.minZ(),area.maxX(),area.maxZ());
            if(groups.count()<1||groups.count()>MAX_GROUPS_PER_CHILD)
                throw new IllegalArgumentException("Native 64-group batch cap violated "+id);
            int c=(int)groups.chunks();
            children.add(new Child(ordinal,id,area,structure,fauna,(int)groups.count(),c));
            groupSum=Math.addExact(groupSum,(int)groups.count());
            chunkSum=Math.addExact(chunkSum,c);
        }
        if(groupSum!=EXACT_GROUPS||chunkSum!=EXACT_CHUNKS||children.size()!=17)
            throw new IllegalArgumentException("Incomplete Contra 1024-group physical child coverage");
        return new Manifest(VERSION,qualification,ecology,frozenRequestSha256,
            ContraV5SectorLineage.PLAN_SHA256,species,children,groupSum,chunkSum);
    }
    /** Exact deterministic manifest regeneration: no altered sector, parent or fauna UUID. */
    public static void verify(Manifest manifest) {
        if(manifest==null||manifest.version()!=VERSION)
            throw new IllegalArgumentException("Missing pinned Contra V5 manifest");
        var expected=create(manifest.qualification(),manifest.ecology(),
            manifest.frozenRequestSha256(),manifest.species());
        if(!expected.equals(manifest))
            throw new IllegalArgumentException("Contra V5 physical child lineage changed");
    }
    private ContraV5SegmentedManifest() {}
}
