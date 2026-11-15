package fr.proutlost.director.api.model;

import java.util.*;

/** Closed-codec DTOs; data never grants authority. */
public final class PhysicalCatalogModels {
    private PhysicalCatalogModels() {}
    public enum State { PENDING, DURABLE, REFUSED, FAILED_SAFE, STALE_SESSION }
    /** Anchor IDs are local to a D0 location. Never collapse two same-named anchors. */
    public record AnchorBinding(ResourceId sourceLocation,ResourceId sourceAnchor,AnchorId directorAnchor) {
        public AnchorBinding {Objects.requireNonNull(sourceLocation);Objects.requireNonNull(sourceAnchor);Objects.requireNonNull(directorAnchor);}
    }
    public record Binding(WorldId directorWorld,ResourceId sourceWorld,ResourceId sourceBuild,
            Fingerprint canonicalSourceSha,Fingerprint bindingSha,
            Map<ResourceId,RegionId> regions,Map<ResourceId,LocationId> locations,
            List<AnchorBinding> anchors) {
        public Binding {
            Objects.requireNonNull(directorWorld);Objects.requireNonNull(sourceWorld);Objects.requireNonNull(sourceBuild);
            Objects.requireNonNull(canonicalSourceSha);Objects.requireNonNull(bindingSha);
            regions=Map.copyOf(regions);locations=Map.copyOf(locations);anchors=List.copyOf(anchors);
            if(regions.size()>1024||locations.size()>1024||anchors.size()>4096)
                throw new IllegalArgumentException("physical binding capacity");
            Set<UUID> used=new HashSet<>();used.add(directorWorld.value());
            for(var id:regions.values())if(!used.add(id.value()))throw new IllegalArgumentException("reused region identity");
            for(var id:locations.values())if(!used.add(id.value()))throw new IllegalArgumentException("cross-kind location identity");
            Set<List<ResourceId>> external=new HashSet<>();
            for(var a:anchors)if(!external.add(List.of(a.sourceLocation(),a.sourceAnchor()))||!used.add(a.directorAnchor().value()))
                throw new IllegalArgumentException("reused/local anchor identity");
        }
    }
    public record Key(WorldId directorWorld,ResourceId sourceWorld,ResourceId sourceBuild,
            String sourceRevision,Fingerprint sourceContentDigest,
            Fingerprint canonicalSourceSha,Fingerprint bindingSha) {
        public Key {
            Objects.requireNonNull(directorWorld);Objects.requireNonNull(sourceWorld);Objects.requireNonNull(sourceBuild);
            Objects.requireNonNull(sourceContentDigest);Objects.requireNonNull(canonicalSourceSha);Objects.requireNonNull(bindingSha);
            if(sourceRevision==null||sourceRevision.isBlank()||sourceRevision.length()>4096)
                throw new IllegalArgumentException("exact bounded D0 revision required");
        }
    }
    public record Document(Key key,Binding binding,BlobContent completeSource) {
        public Document {
            Objects.requireNonNull(key);Objects.requireNonNull(binding);Objects.requireNonNull(completeSource);
            if(!key.directorWorld().equals(binding.directorWorld())||!key.sourceWorld().equals(binding.sourceWorld())
                    ||!key.sourceBuild().equals(binding.sourceBuild())||!key.canonicalSourceSha().equals(binding.canonicalSourceSha())
                    ||!key.bindingSha().equals(binding.bindingSha())||!key.canonicalSourceSha().equals(completeSource.reference().hash())
                    ||completeSource.reference().bytes()<1||completeSource.reference().bytes()>1_048_576
                    ||!completeSource.reference().codecId().equals(PhysicalCatalogFacts.SOURCE_CODEC)
                    ||completeSource.reference().codecVersion()!=1)
                throw new IllegalArgumentException("physical source/binding mismatch");
        }
    }
    public record Readback(Key key,StoreSession session,Sequence cut,BlobRef completeSource,
            Fingerprint projectionDigest,String runtimeIdentity) {
        public Readback {
            Objects.requireNonNull(key);Objects.requireNonNull(session);Objects.requireNonNull(cut);
            Objects.requireNonNull(completeSource);Objects.requireNonNull(projectionDigest);
            if(!key.directorWorld().equals(session.worldId())||cut.value()<1
                    ||!key.canonicalSourceSha().equals(completeSource.hash())
                    ||runtimeIdentity==null||runtimeIdentity.isBlank())
                throw new IllegalArgumentException("committed current-session readback required");
        }
    }
    public record Progress(State state,Key key,Optional<Readback> readback,String reason) {
        public Progress {
            Objects.requireNonNull(state);Objects.requireNonNull(key);Objects.requireNonNull(readback);Objects.requireNonNull(reason);
            if((state==State.DURABLE)!=readback.isPresent()||readback.isPresent()&&!readback.orElseThrow().key().equals(key))
                throw new IllegalArgumentException("physical progress/readback mismatch");
        }
    }
}
