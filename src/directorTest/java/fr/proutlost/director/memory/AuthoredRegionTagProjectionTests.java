package fr.proutlost.director.memory;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.StandaloneLocationImporter;
import java.util.*;
import static fr.proutlost.director.memory.MemoryIntegrationTest.*;

/** Pure source/projection fixtures, never native import or human qualification. */
final class AuthoredRegionTagProjectionTests {
    static void run(){
        var rid=new RegionId(id(19000));var aid=new AnchorId(id(19001));var lid=new LocationId(id(19002));
        var source=new ResourceId("test:authored");var clearing=new TagId("proutlost:clearing");
        var corridor=new TagId("proutlost:fauna_corridor");var signal=new TagId("proutlost:signal_anchor");
        var b=new ImportBounds(-4,60,-4,5,70,5);
        var shape=new Aabb(dimension,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());
        var ir=new ImportedRegion(rid,dimension,b,Set.of(clearing));
        var il=new ImportedLocation(lid,"clearing-with-corridor",Set.of(rid),dimension,b,Set.of(corridor));
        var ia=new ImportedAnchor(aid,lid,dimension,new ImportPosition(0,64,0),Set.of(signal));
        var unsigned=new LocationImport(1,world,source,new Revision(1),new Fingerprint("0".repeat(64)),
            List.of(ir),List.of(il),List.of(ia));
        var input=new LocationImport(1,world,source,unsigned.exportRevision(),
            StandaloneLocationImporter.digest(unsigned),unsigned.regions(),unsigned.locations(),unsigned.anchors());
        var r=new RegionRecord(header(rid.value(),1,0),rid,dimension,RegionStatus.ACTIVE,
            new SupportShape(List.of(shape),List.of(),List.of()),Set.of(aid),new UnitValue(.5),
            Set.of(),Set.of(),new Revision(1),new RegionPersonality(Map.of()),Set.of(new MemoryId(rid.value())));
        var a=new AnchorRecord(header(aid.value(),1,0),aid,Optional.of(lid),Set.of(rid),
            dimension,new BlockPosition(dimension,0,64,0),"region.camp_radius_blocks",
            ia.tags(),AnchorOrigin.IMPORTED,EvidenceStatus.CONFIRMED,true);
        var loc=new LocationRecord(header(lid.value(),1,0),lid,source,il.externalId(),
            dimension,shape,il.tags(),Set.of(aid),input.exportRevision(),true);
        java.util.function.Function<LocationRecord,MemoryView> memory=location->{
            var rows=new ArrayList<AggregateState>(List.of(new RegionAggregate(r),
                new RegionMemoryAggregate(MemoryIntegrationTest.memory(r,0,Set.of())),new AnchorAggregate(a)));
            if(location!=null)rows.add(new LocationAggregate(location));
            return MemoryProjection.of(all,rows);
        };
        var actual=memory.apply(loc);
        var authored=AuthoredRegionTagProjection.fromCommittedImports(List.of(input),actual);
        check(authored.equals(Map.of(rid,Set.of(clearing,corridor))),
            "declared region and location tags remain regional");
        check(actual.anchors().get(aid).tags().equals(Set.of(signal)),
            "regional clearing/corridor never become signal anchor tags");
        var real=new MemorySnapshotProvider(settings).regions(actual,Map.of(),tick(30));
        var merged=AuthoredRegionTagProjection.merge(real,authored);
        check(merged.get(rid).tags().equals(Set.of(clearing,corridor,signal)),
            "regional semantics enrich the real region without removing anchor-derived context");
        check(merged.get(rid).region().equals(real.get(rid).region())
            &&merged.get(rid).presentPlayers().equals(real.get(rid).presentPlayers())
            &&merged.get(rid).camps().equals(real.get(rid).camps())
            &&merged.get(rid).risks().equals(real.get(rid).risks()),
            "authored semantics cannot replace region, participants, camp evidence or risk measurements");
        var unrelated=new RegionId(id(19003));var extra=new HashMap<>(authored);
        extra.put(unrelated,Set.of(clearing));
        check(AuthoredRegionTagProjection.merge(real,extra).keySet().equals(real.keySet()),
            "unrelated authored identifiers never manufacture regions");
        var mutable=new HashSet<>(Set.of(clearing));var pending=new HashMap<RegionId,Set<TagId>>();
        pending.put(rid,mutable);
        var captured=AuthoredRegionTagProjection.merge(real,pending);
        mutable.clear();pending.clear();
        check(captured.get(rid).tags().contains(clearing),
            "later source mutations cannot change a captured semantic snapshot");
        var inactive=new LocationRecord(loc.header(),lid,source,loc.externalId(),dimension,
            shape,loc.tags(),loc.anchorIds(),input.exportRevision(),false);
        check(AuthoredRegionTagProjection.fromCommittedImports(List.of(input),memory.apply(inactive))
            .equals(Map.of(rid,Set.of(clearing))),
            "inactive location does not contribute its corridor classification");
        rejects(()->AuthoredRegionTagProjection.fromCommittedImports(List.of(input),memory.apply(null)),
            "cold location cannot supply an empty successful semantic view");
        var stale=new LocationRecord(loc.header(),lid,source,loc.externalId(),dimension,
            shape,loc.tags(),loc.anchorIds(),new Revision(2),true);
        rejects(()->AuthoredRegionTagProjection.fromCommittedImports(List.of(input),memory.apply(stale)),
            "old authored source cannot borrow a newer actual location revision");
        rejects(()->AuthoredRegionTagProjection.fromCommittedImports(List.of(input,input),actual),
            "duplicate source-region identity cannot merge silently");
        var wrongWorld=new MemoryView(new ScopeSet(new WorldId(id(19004)),Set.of()),
            actual.revisions(),actual.regions(),actual.operationalPlayers(),actual.significantPlayers(),
            actual.regionRecords(),actual.anchors(),actual.locations());
        rejects(()->AuthoredRegionTagProjection.fromCommittedImports(List.of(input),wrongWorld),
            "authored source cannot cross world identity");
    }
}
