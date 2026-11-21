package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Dry-run only, NEVER deletes/overwrites metadata or historical evidence.
 * Requires TWO independently verified full root snapshots (current+previous).
 * No page is eligible unless it is absent from BOTH complete SHA-pinned catalogs.
 * Future retention executor needs a separate cold proof and safety tests.
 */
public final class ImmutableBucketedNbtRetentionPlan {
    private static final String INDEX="__worldprep_bucket_index_v1";
    public record Plan(int allPages,int reachableFromEitherRoot,int detachedPages,
                       long detachedCompressedBytes,List<String> orphanCandidates,
                       boolean explicitlyDryRun) {}

    public static Plan inspect(CompoundTag current, CompoundTag previous,
                               Path checkpoint, String store) throws IOException {
        if(current==null||previous==null||!ImmutableBucketedNbtCheckpoint.isPaged(current)
                ||!ImmutableBucketedNbtCheckpoint.isPaged(previous))
            throw new IOException("Two complete paged checkpoint roots are required; refuse orphaning history");
        // Full cold read-back revalidates every referenced page including SHA,
        // duplicate keys, UUIDs, ordering and the complete NBT schema.
        ImmutableBucketedNbtCheckpoint.expand(current,checkpoint,store);
        ImmutableBucketedNbtCheckpoint.expand(previous,checkpoint,store);

        var keep=new HashSet<Path>();
        addReferences(current,checkpoint,store,keep);
        addReferences(previous,checkpoint,store,keep);
        Path dir=checkpoint.toAbsolutePath().getParent().resolve("worldprep-paged-v1").resolve(store);
        if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir))throw new IOException("No safe page root");
        int count=0,pinned=0;
        long orphanBytes=0;
        var orphans=new ArrayList<String>();
        try(var paths=Files.walk(dir)){
            for(var p:paths.toList()){
                if(Files.isSymbolicLink(p))throw new IOException("Symlink within immutable pages");
                if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))continue;
                if(p.getFileName().toString().endsWith(".partial"))
                    continue; // historical failed/unfinished publication evidence NEVER swept
                if(!p.getFileName().toString().endsWith(".nbt.gz"))
                    throw new IOException("Unknown metadata evidence file, refuse sweep");
                count++;
                if(keep.contains(p.toAbsolutePath().normalize()))pinned++;
                else{
                    orphanBytes+=Files.size(p);
                    orphans.add(dir.relativize(p).toString());
                }
            }
        }
        if(pinned!=keep.size())
            throw new IOException("Pinned page missing during retention inspection");
        return new Plan(count,pinned,orphans.size(),orphanBytes,List.copyOf(orphans),true);
    }
    private static void addReferences(CompoundTag root,Path checkpoint,
                                      String store,Set<Path> keep) throws IOException {
        var index=root.getCompound("data").getCompound(INDEX);
        if(index.getInt("version")!=ImmutableBucketedNbtCheckpoint.VERSION
                ||index.getInt("bucketCount")!=ImmutableBucketedNbtCheckpoint.BUCKETS
                ||!store.equals(index.getString("store")))
            throw new IOException("Index identity does not match retention target");
        for(var entry:index.getList("sections",Tag.TAG_COMPOUND)){
            var section=(CompoundTag)entry;
            String name=section.getString("name");
            if(!Set.of("areas","jobs","plans","journals","snapshots").contains(name))
                throw new IOException("Unknown NBT section");
            for(var tag:section.getList("buckets",Tag.TAG_COMPOUND)){
                var bucket=(CompoundTag)tag;
                String digest=bucket.getString("sha256");
                if(!digest.matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid page digest in retention catalog");
                Path ref=checkpoint.toAbsolutePath().getParent()
                        .resolve("worldprep-paged-v1").resolve(store)
                        .resolve(name).resolve(digest+".nbt.gz").normalize();
                keep.add(ref);
            }
        }
    }
    private ImmutableBucketedNbtRetentionPlan(){}
}
