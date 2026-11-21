package fr.proutlost.worldprep.world;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Generation-free port: implementations may only use non-creating chunk lookups. */
public final class ExistingChunkAccess<C> {
 public interface Lookup<C> { Optional<Chunk<C>> findExisting(String dimension,int chunkX,int chunkZ); }
 public record Chunk<C>(C value,String status) { public Chunk { Objects.requireNonNull(value); Objects.requireNonNull(status); } }
 public enum Refusal { MISSING_CHUNK, INCOMPLETE_STATUS, WRONG_DIMENSION, OUTSIDE_AREA, OUTSIDE_BORDER, OUTSIDE_BUILD_HEIGHT }
 public record Result<C>(Chunk<C> chunk,Refusal refusal) { public boolean accepted(){return chunk!=null;} }
 private final Lookup<C> lookup; private final Set<String> acceptedStatuses;
 public ExistingChunkAccess(Lookup<C> lookup,Set<String> acceptedStatuses){this.lookup=Objects.requireNonNull(lookup);this.acceptedStatuses=Set.copyOf(acceptedStatuses);if(this.acceptedStatuses.isEmpty())throw new IllegalArgumentException("Accepted statuses required");}
 public Result<C> require(String dimension,int chunkX,int chunkZ){
   Optional<Chunk<C>> found=lookup.findExisting(dimension,chunkX,chunkZ); if(found.isEmpty())return new Result<>(null,Refusal.MISSING_CHUNK);
   if(!acceptedStatuses.contains(found.get().status()))return new Result<>(null,Refusal.INCOMPLETE_STATUS); return new Result<>(found.get(),null);
 }
}
