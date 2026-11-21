package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import static fr.proutlost.contract.director.Vocabulary.*;
import java.time.Instant;
import java.util.*;

/** Neutral schema 1.0.0 producer. No Director implementation imports or narrative authority.
 * READY is a sealed physical snapshot state, NOT proof of an import into a running Director.
 * Runtime handoff remains NOT_READY until a separately retained real import receipt exists.
 */
public final class PhysicalCatalogExporter {
    public record Site(Id location,Revision revision,SourceType source,Id structureInstance,Id sourceRef,
                       List<Bounds> geometry,Set<SemanticRegionId> regions,List<Anchor> anchors,
                       List<InteractionSurface> surfaces,List<LocationCapability> capabilities,
                       List<ProtectedZone> protection,List<AnchorRef> recovery,
                       List<EvidenceRef> physicalEvidence,List<EvidenceRef> diskEvidence,
                       Set<Id> tags,Set<Channel> presentationChannels) {
        public Site {
            geometry=List.copyOf(geometry);regions=Set.copyOf(regions);anchors=List.copyOf(anchors);
            surfaces=List.copyOf(surfaces);capabilities=List.copyOf(capabilities);protection=List.copyOf(protection);
            recovery=List.copyOf(recovery);physicalEvidence=List.copyOf(physicalEvidence);diskEvidence=List.copyOf(diskEvidence);
            tags=Set.copyOf(tags);presentationChannels=Set.copyOf(presentationChannels);
        }
        /** Existing physical producers declare no semantic tags or presentation authority.
         * Supplying anchors/capabilities alone must never infer either declaration. */
        public Site(Id location,Revision revision,SourceType source,Id structureInstance,Id sourceRef,
                    List<Bounds> geometry,Set<SemanticRegionId> regions,List<Anchor> anchors,
                    List<InteractionSurface> surfaces,List<LocationCapability> capabilities,
                    List<ProtectedZone> protection,List<AnchorRef> recovery,
                    List<EvidenceRef> physicalEvidence,List<EvidenceRef> diskEvidence) {
            this(location,revision,source,structureInstance,sourceRef,geometry,regions,anchors,
                surfaces,capabilities,protection,recovery,physicalEvidence,diskEvidence,Set.of(),Set.of());
        }
    }
    public record Result(String status,String json,String reason) {}
    /** Verifies referenced immutable evidence; implementations must check their physical authority. */
    public interface EvidenceVerifier {void verify(Site site);}
    public static Result export(Id world,Id build,Revision revision,Instant validatedAt,List<SemanticRegion> regions,
                                List<Site> sites,EvidenceVerifier verifier) {
        if(sites.isEmpty()||sites.size()>Limits.MAX_ITEMS||regions.size()>Limits.MAX_ITEMS)
            return new Result("NOT_READY",null,"Empty/oversized physical catalogue");
        try {
            var entries=new ArrayList<StaticLocationCatalogEntry>();var allEvidence=new LinkedHashSet<EvidenceRef>();
            // Identical discoveries may share one physical instance; conflicting aliases never do.
            var unique=new TreeMap<Id,Site>();var instances=new HashMap<Id,Id>();
            for(var site:sites) {
                var previous=unique.putIfAbsent(site.location(),site);
                if(previous!=null&&!previous.equals(site))throw new IllegalArgumentException("Conflicting physical location identity");
                if(site.structureInstance()!=null) {
                    var owner=instances.putIfAbsent(site.structureInstance(),site.location());
                    if(owner!=null&&!owner.equals(site.location()))throw new IllegalArgumentException("Aliased structure instance identity");
                }
            }
            for(var site:unique.values()) {
                if(site.physicalEvidence().isEmpty()||site.diskEvidence().isEmpty())throw new IllegalArgumentException("Physical AND disk evidence required");
                verifier.verify(site);
                var evidence=new ArrayList<>(site.physicalEvidence());evidence.addAll(site.diskEvidence());allEvidence.addAll(evidence);
                var raw=new StaticLocationCatalogEntry(world,SchemaVersion.BASE,build,site.location(),site.revision(),site.source(),site.structureInstance(),site.regions(),
                    site.geometry(),site.tags(),site.anchors(),site.surfaces(),site.capabilities(),site.protection(),site.presentationChannels(),site.recovery(),
                    new Provenance(new Id("proutlost:worldprep"),site.sourceRef(),null,evidence),evidence,new Digest("0".repeat(64)));
                entries.add(new StaticLocationCatalogEntry(raw.world_id(),raw.schema_version(),raw.build_id(),raw.location_id(),raw.revision(),raw.source_type(),
                    raw.structure_instance_id(),raw.semantic_region_ids(),raw.geometry(),raw.tags(),raw.anchors(),raw.surfaces(),raw.capabilities(),raw.protection(),
                    raw.presentation_channels(),raw.recovery_anchors(),raw.provenance(),raw.validation_evidence(),StrictCodec.contentDigest(raw)));
            }
            var raw=new CatalogSnapshot(world,SchemaVersion.BASE,build,revision,new Digest("0".repeat(64)),CatalogState.READY,validatedAt,
                List.copyOf(allEvidence),List.copyOf(regions),entries);
            var sealed=new CatalogSnapshot(world,SchemaVersion.BASE,build,revision,StrictCodec.contentDigest(raw),CatalogState.READY,validatedAt,
                raw.validation_evidence(),raw.regions(),raw.entries());
            CatalogValidation.requireSealed(sealed);
            return new Result("PHYSICAL_EXPORT_SEALED_DIRECTOR_NOT_READY",StrictCodec.encode(sealed),"Real runtime import not attested");
        } catch(RuntimeException failure) {return new Result("NOT_READY",null,failure.getMessage());}
    }
    private PhysicalCatalogExporter(){}
}
