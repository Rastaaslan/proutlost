package fr.proutlost.director.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Derived cold-index GC; never garbage-collects an authoritative checkpoint,
 * WAL segment, recovery manifest, blob, or any file of unknown format.
 *
 * Caller runs on J04's exclusive writer AFTER authoritative retention has
 * chosen all pinned generations and finished deleting pruned manifests.
 * Index sidecars are reproducible cache data, not durable gameplay records.
 */
final class ColdIndexRetention {
    private static final Pattern SIDE_CAR =
        Pattern.compile("([1-9][0-9]*)\\.(wdidx|wdagg)");
    private ColdIndexRetention() {}

    static int prune(Path checkpointDirectory,long currentGeneration,
                     Set<Long> retainedGenerations,
                     JournalStore.IoProbe probe)throws IOException {
        Objects.requireNonNull(checkpointDirectory);
        Objects.requireNonNull(retainedGenerations);
        Objects.requireNonNull(probe);
        if(currentGeneration<1 || !retainedGenerations.contains(currentGeneration) ||
           retainedGenerations.stream().anyMatch(g->g==null || g<1 || g>currentGeneration))
            throw new IOException("unsafe cold index retention cut");
        var directory=checkpointDirectory.resolve("cold-index");
        if(Files.isSymbolicLink(directory))
            throw new IOException("cold index directory is a symlink");
        if(!Files.exists(directory))return 0;
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("invalid cold index directory");

        // Preflight ALL candidate entries before any deletion. A suspicious
        // .wdidx/.wdagg never becomes a reason to delete something outside J04.
        var removals=new ArrayList<Path>();
        try(var stream=Files.list(directory)) {
            for(var file:stream.toList()) {
                String name=file.getFileName().toString();
                var match=SIDE_CAR.matcher(name);
                if(!match.matches())continue;
                long generation;
                try {generation=Long.parseLong(match.group(1));}
                catch(NumberFormatException invalid) {
                    throw new IOException("oversized cold index generation",invalid);
                }
                if(Files.isSymbolicLink(file) ||
                   !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("unsafe cold index sidecar file");
                if(generation>=currentGeneration ||
                   retainedGenerations.contains(generation))
                    continue;
                // Crash-safe conservative sweep. A retired generation might
                // still have a checkpoint or manifest after interrupted GC.
                // Never delete its index until both authoritative paths vanish.
                if(Files.exists(checkpointDirectory.resolve(generation+".manifest"),
                        LinkOption.NOFOLLOW_LINKS) ||
                   Files.exists(checkpointDirectory.resolve(generation+".snapshot"),
                        LinkOption.NOFOLLOW_LINKS))
                    continue;
                removals.add(file);
            }
        }
        int removed=0;
        for(var file:removals) {
            probe.at("BEFORE_COLD_INDEX_CLEANUP");
            Files.delete(file);
            removed++;
            probe.at("COLD_INDEX_CLEANUP");
        }
        if(removed>0)DurableFiles.directory(directory);
        return removed;
    }
}
