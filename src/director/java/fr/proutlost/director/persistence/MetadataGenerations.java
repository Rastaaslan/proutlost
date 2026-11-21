package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.LocationTransferContract;
import fr.proutlost.director.api.codec.MetadataPreparationClockContract;
import fr.proutlost.director.api.codec.MetadataAttemptContract;
import java.io.*;
import java.util.*;
import static fr.proutlost.director.persistence.StoreProjection.require;

/** J04 writer-owned immutable metadata generations. Staging never changes visible heads.
 * Large states live in verified J04 blobs; the hot index holds only key/revision/page references.
 */
final class MetadataGenerations {
    static final int CACHE_ENTRIES=512;
    static final long MAX_STAGED_BYTES=BlobContent.MAX_BATCH_BYTES;
    static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    static final ModelCodec<LocationImport> SOURCE=new ModelCodec<>(LocationImport.class);
    static final Comparator<AggregateKey> ORDER=Comparator.comparing(k->k.kind()+":"+k.id());
    @FunctionalInterface interface Reader {BlobContent read(BlobRef reference)throws IOException;}
    record Stage(LocationTransferManifest transfer,Map<Integer,DurableMetadataSourcePage> sourcePages,
                 Map<Integer,DurableMetadataStage> pages,Epoch attempt,Optional<Tick> plannedTick,long retainedStageBytes,Set<BlobRef> retainedStagePages){
        Stage {sourcePages=Map.copyOf(sourcePages);pages=Map.copyOf(pages);retainedStagePages=Set.copyOf(retainedStagePages);Objects.requireNonNull(attempt);Objects.requireNonNull(plannedTick);require(retainedStageBytes>=0&&retainedStageBytes<=MAX_STAGED_BYTES,"metadata retained stage capacity");}
    }
    record LocationIdentity(ResourceId source,String externalId){}
    record Head(ResourceId source,Fingerprint generation,BlobRef page,int ordinal,Revision revision,
                Sequence sequence,Fingerprint transactionHash,Optional<RegionStatus> regionStatus,Tick updatedTick){}
    static Fingerprint identity(LocationTransferManifest transfer){return LocationTransferContract.identity(transfer);}
    static Revision revision(StoreProjection projection,AggregateKey key){
        var resident=projection.aggregates.get(key);var cold=projection.metadataHeads.get(key);
        Revision found=resident==null?new Revision(0):resident.state().header().revision();
        return cold!=null&&cold.revision().compareTo(found)>0?cold.revision():found;
    }
    static boolean coldWins(StoreProjection projection,AggregateKey key){
        var cold=projection.metadataHeads.get(key);var resident=projection.aggregates.get(key);
        return cold!=null&&(resident==null||cold.revision().compareTo(resident.state().header().revision())>0);
    }
    static MetadataGenerationPage page(DurableMetadataStage stage,Reader reader)throws IOException{
        var content=reader.read(stage.page());var page=PAGE.decode(content.bytes());
        var transfer=stage.transfer();
        require(page.generation().equals(identity(transfer))&&page.sourceNamespace().equals(transfer.sourceNamespace())
            &&page.exportRevision().equals(transfer.exportRevision())&&page.exportDigest().equals(transfer.exportDigest())
            &&page.pageIndex()==stage.pageIndex()&&page.pageCount()==stage.pageCount(),"metadata page identity");
        require(Arrays.equals(content.bytes(),PAGE.encode(page)),"metadata canonical page bytes");
        return page;
    }
    private static Set<ResourceId> sources(StoreProjection projection){
        var sources=new HashSet<>(projection.metadataSources.keySet());sources.addAll(projection.metadataLegacySources.keySet());sources.addAll(projection.metadataStaging.keySet());return sources;
    }
    private static Stage begin(StoreProjection projection,LocationTransferManifest transfer){
        require(!projection.metadataCancelled.contains(identity(transfer)),"cancelled metadata generation retained");
        var legacy=projection.metadataLegacySources.get(transfer.sourceNamespace());
        if(legacy!=null)require(transfer.exportRevision().compareTo(legacy.exportRevision())>0
            ||transfer.exportRevision().equals(legacy.exportRevision())&&transfer.exportDigest().equals(legacy.exportDigest()),"metadata legacy source revision conflict");
        var source=transfer.sourceNamespace();var active=projection.metadataSources.get(source);
        if(active!=null){
            int order=transfer.exportRevision().compareTo(active.transfer().exportRevision());
            require(order>0||order==0&&transfer.equals(active.transfer()),"metadata source revision conflict");
        }
        var previous=projection.metadataStaging.get(source);
        if(previous!=null&&!previous.transfer().equals(transfer)){
            require(projection.metadataCancelled.contains(identity(previous.transfer()))||active!=null&&active.transfer().equals(previous.transfer()),"unfinished metadata generation retained");
            previous=null;
        }
        if(previous!=null)return previous;
        require(sources(projection).contains(source)||sources(projection).size()<32,"metadata source capacity");
        return new Stage(transfer,Map.of(),Map.of(),new Epoch(0),Optional.ofNullable(projection.metadataPreparationClocks.get(identity(transfer))),0,Set.of());
    }
    static void source(StoreProjection projection,DurableMetadataSourcePage value,Reader reader)throws IOException{
        var stage=begin(projection,value.transfer());
        // Verify bytes before recording a resumable immutable fragment receipt.
        reader.read(value.page());
        var pages=new HashMap<>(stage.sourcePages());var old=pages.putIfAbsent(value.pageIndex(),value);
        require(old==null||old.equals(value),"metadata source page conflict");
        projection.metadataStaging.put(value.transfer().sourceNamespace(),new Stage(stage.transfer(),pages,stage.pages(),stage.attempt(),stage.plannedTick(),stage.retainedStageBytes(),stage.retainedStagePages()));
    }
    static void stage(StoreProjection projection,DurableMetadataStage value,Reader reader)throws IOException{
        stage(projection,value,new Epoch(0),reader);
    }
    private static void stage(StoreProjection projection,DurableMetadataStage value,Epoch attempt,Reader reader)throws IOException{
        var stage=begin(projection,value.transfer());require(stage.attempt().equals(attempt),"metadata stage attempt CAS");var prepared=page(value,reader);
        for(var state:prepared.states()){
            if(attempt.value()==0)rememberClock(projection,value.transfer(),state.header().updatedTick());
            else require(stage.plannedTick().filter(state.header().updatedTick()::equals).isPresent(),"metadata attempt planned clock conflict");
        }
        var pages=new HashMap<>(stage.pages());var old=pages.putIfAbsent(value.pageIndex(),value);
        require(old==null||old.equals(value),"metadata stage page conflict");
        require(pages.values().stream().allMatch(p->p.pageCount()==value.pageCount()),"metadata page count conflict");
        require(pages.values().stream().mapToLong(p->p.page().bytes()).sum()<=MAX_STAGED_BYTES,"metadata staged byte capacity");
        var retainedPages=new HashSet<>(stage.retainedStagePages());long retained=stage.retainedStageBytes();
        if(retainedPages.add(value.page()))retained=Math.addExact(retained,value.page().bytes());
        require(retained<=MAX_STAGED_BYTES,"metadata retained attempt byte capacity");
        var tick=stage.plannedTick().or(()->Optional.ofNullable(projection.metadataPreparationClocks.get(identity(value.transfer()))));
        projection.metadataStaging.put(value.transfer().sourceNamespace(),new Stage(stage.transfer(),stage.sourcePages(),pages,stage.attempt(),tick,retained,retainedPages));
    }
    private static void rememberClock(StoreProjection projection,LocationTransferManifest transfer,Tick tick){
        var old=projection.metadataPreparationClocks.putIfAbsent(identity(transfer),tick);
        require(old==null||old.equals(tick),"metadata generation planned clock conflict");
        projection.metadataClockFloor=Math.max(projection.metadataClockFloor,tick.value());
    }
    private static boolean preparedClock(StoreProjection projection,DirectorFact fact,WorldId world){
        var decoded=MetadataPreparationClockContract.decode(fact,world);if(decoded.isEmpty())return false;
        var prepared=decoded.orElseThrow();var stage=begin(projection,prepared.transfer());
        rememberClock(projection,prepared.transfer(),prepared.tick());
        projection.metadataStaging.put(prepared.transfer().sourceNamespace(),new Stage(stage.transfer(),stage.sourcePages(),stage.pages(),stage.attempt(),stage.plannedTick().or(()->Optional.of(prepared.tick())),stage.retainedStageBytes(),stage.retainedStagePages()));return true;
    }
    static LocationImport source(Stage stage,Reader reader)throws IOException{
        var transfer=stage.transfer();
        require(stage.sourcePages().size()==transfer.pageHashes().size(),"metadata source incomplete");
        var bytes=new ByteArrayOutputStream(transfer.sourceBytes());
        for(int i=0;i<transfer.pageHashes().size();i++){
            var page=Objects.requireNonNull(stage.sourcePages().get(i),"missing metadata source fragment");
            require(page.transfer().equals(transfer)&&page.pageIndex()==i,"metadata source fragment identity");
            bytes.writeBytes(reader.read(page.page()).bytes());
        }
        byte[] raw=bytes.toByteArray();require(raw.length==transfer.sourceBytes()
            &&BlobContent.hash(raw).equals(transfer.sourceSha256()),"metadata source bytes/digest");
        var value=SOURCE.decode(raw);
        require(value.worldId().equals(transfer.worldId())&&value.sourceNamespace().equals(transfer.sourceNamespace())
            &&value.exportRevision().equals(transfer.exportRevision())&&value.exportDigest().equals(transfer.exportDigest())
            &&value.exportDigest().equals(LocationTransferContract.digest(value))
            &&(long)value.regions().size()+value.locations().size()+value.anchors().size()==transfer.entryCount()
            &&Arrays.equals(raw,SOURCE.encode(value)),"metadata original source identity");
        return value;
    }
    static void activate(StoreProjection projection,DurableMetadataActivation activation,Reader reader,
                         long sequence,Fingerprint transactionHash)throws IOException{
        activate(projection,activation,new Epoch(0),reader,sequence,transactionHash);
    }
    private static void activate(StoreProjection projection,DurableMetadataActivation activation,Epoch attempt,Reader reader,
                         long sequence,Fingerprint transactionHash)throws IOException{
        var transfer=activation.transfer();var source=transfer.sourceNamespace();var id=identity(transfer);
        var current=begin(projection,transfer);require(current.attempt().equals(attempt),"metadata activation attempt CAS");
        var active=projection.metadataSources.get(source);
        if(active!=null&&identity(active.transfer()).equals(id)){
            require(active.transfer().equals(transfer)&&active.pages().size()==activation.pageCount(),"metadata replay activation conflict");return;
        }
        require(activation.previousGeneration().equals(active==null?Optional.empty():Optional.of(identity(active.transfer()))),"metadata source head CAS");
        // Source revisions can advance through legacy APPLIED without changing any
        // aggregate (e.g. two empty exports). Key CAS alone cannot detect that race.
        // Recheck source authority at the same writer cut as the visibility pivot.
        var stage=begin(projection,transfer);
        require(stage.transfer().equals(transfer)&&stage.pages().size()==activation.pageCount(),"metadata generation incomplete");
        require(!projection.metadataCancelled.contains(id),"cancelled metadata activation");
        var input=source(stage,reader);var previous=active==null?projection.metadataLegacySources.get(source):source(active,reader);
        var expected=new HashSet<AggregateKey>();var memories=new HashSet<AggregateKey>();
        var regions=new HashMap<RegionId,ImportedRegion>();input.regions().forEach(value->{regions.put(value.regionId(),value);
            expected.add(new AggregateKey(AggregateKind.REGION,value.regionId().value()));memories.add(new AggregateKey(AggregateKind.REGION_MEMORY,value.regionId().value()));});
        var locations=new HashMap<LocationId,ImportedLocation>();input.locations().forEach(value->{locations.put(value.locationId(),value);expected.add(new AggregateKey(AggregateKind.LOCATION,value.locationId().value()));});
        var anchors=new HashMap<AnchorId,ImportedAnchor>();input.anchors().forEach(value->{anchors.put(value.anchorId(),value);expected.add(new AggregateKey(AggregateKind.ANCHOR,value.anchorId().value()));});
        var retired=new HashSet<AggregateKey>();
        if(previous!=null){previous.regions().forEach(value->{if(!regions.containsKey(value.regionId()))retired.add(new AggregateKey(AggregateKind.REGION,value.regionId().value()));});
            previous.locations().forEach(value->{if(!locations.containsKey(value.locationId()))retired.add(new AggregateKey(AggregateKind.LOCATION,value.locationId().value()));});
            previous.anchors().forEach(value->{if(!anchors.containsKey(value.anchorId()))retired.add(new AggregateKey(AggregateKind.ANCHOR,value.anchorId().value()));});}
        expected.addAll(retired);var guarded=new HashSet<AggregateKey>();
        var seen=new HashSet<AggregateKey>();var identities=new HashMap<UUID,AggregateKind>();Tick plannedTick=null;
        // Check every current aggregate revision before changing ANY candidate head.
        // Candidate is private; failed validation cannot publish a partial generation.
        for(int i=0;i<activation.pageCount();i++){
            var record=Objects.requireNonNull(stage.pages().get(i),"metadata missing stage page");
            require(record.pageCount()==activation.pageCount(),"metadata activation page count");
            var page=page(record,reader);
            for(var guard:page.beforeRevisions().entrySet()){require(revision(projection,guard.getKey()).equals(guard.getValue()),"metadata aggregate CAS");guarded.add(guard.getKey());}
            for(var state:page.states()){
                var key=state.key();require(seen.add(key),"metadata duplicate generation key");
                if(plannedTick==null)plannedTick=state.header().updatedTick();
                else require(plannedTick.equals(state.header().updatedTick()),"metadata generation planned tick changed");
                require(state.header().lastSequence().value()<=sequence,"metadata aggregate future sequence");
                var resident=projection.aggregates.get(key);var cold=projection.metadataHeads.get(key);
                var priorTick=coldWins(projection,key)?cold.updatedTick():resident==null?new Tick(0):resident.state().header().updatedTick();
                require(state.header().updatedTick().compareTo(priorTick)>=0,"metadata timestamp regression");
                var earlier=identities.putIfAbsent(key.id(),key.kind());
                boolean regionPair=earlier==AggregateKind.REGION&&key.kind()==AggregateKind.REGION_MEMORY
                    ||earlier==AggregateKind.REGION_MEMORY&&key.kind()==AggregateKind.REGION;
                require(earlier==null||earlier==key.kind()||regionPair,"metadata generation identity kind collision");
                require(revision(projection,key).equals(page.beforeRevisions().get(key)),"metadata aggregate CAS");
                // REGION/LOCATION ScopeRef revisions also accept legacy memory
                // deltas. They are distinct from RegionMemory's AggregateKey
                // revision and must never be reset by a lower metadata header.
                // Preserve the old native import's scope CAS, in addition to the
                // staged per-key CAS; a divergent producer remains a conflict.
                ScopeKind metadataScope=switch(key.kind()){
                    case REGION->ScopeKind.REGION;case LOCATION->ScopeKind.LOCATION;default->null;
                };
                if(metadataScope!=null)require(projection.revisions.getOrDefault(
                    new ScopeRef(metadataScope,key.id()),new Revision(0)).equals(page.beforeRevisions().get(key)),
                    "metadata scope revision CAS");
                var owner=projection.metadataOwners.get(key);
                require((owner!=null&&owner.equals(source))||owner==null&&revision(projection,key).value()==0,"metadata cross-source/origin identity");
                require(expected.contains(key)||memories.contains(key),"metadata state outside exact source delta");
                if(retired.contains(key))require(state instanceof RegionAggregate region&&region.value().status()==RegionStatus.ARCHIVED
                    ||state instanceof LocationAggregate location&&!location.value().active()&&location.value().importRevision().equals(transfer.exportRevision())
                    ||state instanceof AnchorAggregate anchor&&!anchor.value().active(),"metadata removed identity not retired");
                else validateSourceState(state,input,regions,locations,anchors);
                for(var known:projection.metadataOwners.keySet())if(known.id().equals(key.id())){
                    boolean pair=known.kind()==AggregateKind.REGION&&key.kind()==AggregateKind.REGION_MEMORY
                        ||known.kind()==AggregateKind.REGION_MEMORY&&key.kind()==AggregateKind.REGION;
                    require((known.kind()==key.kind()||pair)&&source.equals(projection.metadataOwners.get(known)),"metadata historical identity collision");
                }
                for(var known:projection.aggregates.keySet())if(known.id().equals(key.id())){
                    boolean pair=known.kind()==AggregateKind.REGION&&key.kind()==AggregateKind.REGION_MEMORY
                        ||known.kind()==AggregateKind.REGION_MEMORY&&key.kind()==AggregateKind.REGION;
                    require(known.kind()==key.kind()||pair,"metadata operational identity kind collision");
                }
                if(state instanceof LocationAggregate location){
                    var value=location.value();require(value.sourceNamespace().equals(source),"metadata location source");
                    var identity=new LocationIdentity(source,value.externalId());
                    var known=projection.metadataLocationIds.get(identity);var external=projection.metadataExternalIds.get(value.locationId());
                    require(known==null||known.equals(value.locationId()),"metadata external identity changed");
                    require(external==null||external.equals(identity),"metadata location external id changed");
                    projection.metadataLocationIds.put(identity,value.locationId());projection.metadataExternalIds.put(value.locationId(),identity);
                }
            }
        }
        require(seen.containsAll(expected),"metadata source coverage incomplete");
        for(var key:memories)require(seen.contains(key)||guarded.contains(key)&&revision(projection,key).value()>0,"metadata region memory coverage incomplete");
        for(int i=0;i<activation.pageCount();i++){
            var record=stage.pages().get(i);var page=page(record,reader);
            for(int n=0;n<page.states().size();n++){
                var state=page.states().get(n);var key=state.key();
                projection.metadataHeads.put(key,new Head(source,id,record.page(),n,state.header().revision(),new Sequence(sequence),transactionHash,state instanceof RegionAggregate region?Optional.of(region.value().status()):Optional.empty(),state.header().updatedTick()));
                projection.metadataOwners.put(key,source);
                projection.metadataHeadVersions.put(key.kind(),sequence);
                MetadataSelectionCensus.update(projection,state,sequence);
                if(state instanceof LocationAggregate location){var value=location.value();var identity=new LocationIdentity(source,value.externalId());
                    projection.metadataLocationIds.put(identity,value.locationId());projection.metadataExternalIds.put(value.locationId(),identity);}
                ScopeKind kind=switch(key.kind()){case REGION->ScopeKind.REGION;case LOCATION->ScopeKind.LOCATION;default->null;};
                if(kind!=null)projection.revisions.put(new ScopeRef(kind,key.id()),state.header().revision());
            }
        }
        projection.metadataSources.put(source,stage);
    }


    /** Closed versioned attempts reuse DurableFact without changing legacy WAL DTOs. */
    static boolean attempt(StoreProjection projection,DirectorFact fact,WorldId world,Reader reader,
                           long sequence,Fingerprint transactionHash)throws IOException{
        boolean reserved=fact.factType().equals(MetadataAttemptContract.REPREPARE)||fact.factType().equals(MetadataAttemptContract.STAGE)
            ||fact.factType().equals(MetadataAttemptContract.ACTIVATE);
        if(!reserved)return false;
        var replay=projection.facts.get(fact.factId());
        if(replay!=null){require(replay.equals(fact),"immutable metadata attempt fact conflict");return true;}
        var prepared=MetadataAttemptContract.reprepare(fact,world);
        if(prepared.isPresent()){
            var value=prepared.orElseThrow();var stage=begin(projection,value.transfer());
            require(stage.attempt().equals(value.expectedAttempt()),"metadata reprepare attempt CAS");
            var active=projection.metadataSources.get(value.transfer().sourceNamespace());
            require(value.previousGeneration().equals(active==null?Optional.empty():Optional.of(identity(active.transfer()))),"metadata reprepare source head CAS");
            require(active==null||!active.transfer().equals(value.transfer()),"active metadata generation cannot be reprepared");
            require(projection.metadataPreparationClocks.containsKey(identity(value.transfer())),"metadata reprepare lacks original durable clock");
            require(stage.plannedTick().filter(t->value.tick().compareTo(t)<0).isEmpty(),"metadata reprepare clock regression");
            projection.metadataClockFloor=Math.max(projection.metadataClockFloor,value.tick().value());
            projection.metadataStaging.put(value.transfer().sourceNamespace(),new Stage(stage.transfer(),stage.sourcePages(),Map.of(),
                stage.attempt().plus(1),Optional.of(value.tick()),stage.retainedStageBytes(),stage.retainedStagePages()));return true;
        }
        var staged=MetadataAttemptContract.staged(fact,world);
        if(staged.isPresent()){var value=staged.orElseThrow();stage(projection,value.value(),value.attempt(),reader);return true;}
        var activated=MetadataAttemptContract.activated(fact,world).orElseThrow();
        activate(projection,activated.value(),activated.attempt(),reader,sequence,transactionHash);return true;
    }

    private static Aabb bounds(DimensionId dimension,ImportBounds bounds){return new Aabb(dimension,bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxXExclusive(),bounds.maxYExclusive(),bounds.maxZExclusive());}
    private static void validateSourceState(AggregateState state,LocationImport input,Map<RegionId,ImportedRegion> regions,
            Map<LocationId,ImportedLocation> locations,Map<AnchorId,ImportedAnchor> anchors){
        if(state instanceof RegionAggregate region){var value=region.value();var original=Objects.requireNonNull(regions.get(value.regionId()),"metadata region absent source");
            require(value.dimension().equals(original.dimension())&&value.status()==RegionStatus.ACTIVE
                &&value.supportShape().boxes().contains(bounds(original.dimension(),original.bounds())),"metadata source region mismatch");}
        else if(state instanceof LocationAggregate location){var value=location.value();var original=Objects.requireNonNull(locations.get(value.locationId()),"metadata location absent source");
            var ownedAnchors=new HashSet<AnchorId>();for(var anchor:input.anchors())if(anchor.locationId().equals(value.locationId()))ownedAnchors.add(anchor.anchorId());
            require(value.active()&&value.sourceNamespace().equals(input.sourceNamespace())&&value.externalId().equals(original.externalId())
                &&value.dimension().equals(original.dimension())&&value.bounds().equals(bounds(original.dimension(),original.bounds()))
                &&value.tags().equals(original.tags())&&value.anchorIds().equals(ownedAnchors)&&value.importRevision().equals(input.exportRevision()),"metadata source location mismatch");}
        else if(state instanceof AnchorAggregate anchor){var value=anchor.value();var original=Objects.requireNonNull(anchors.get(value.anchorId()),"metadata anchor absent source");
            var location=Objects.requireNonNull(locations.get(original.locationId()),"metadata anchor source location");var position=original.position();
            require(value.active()&&value.locationId().equals(Optional.of(original.locationId()))&&value.regionIds().equals(location.regionIds())
                &&value.dimension().equals(original.dimension())&&value.position().equals(new BlockPosition(original.dimension(),position.x(),position.y(),position.z()))
                &&value.origin()==AnchorOrigin.IMPORTED&&value.evidence()==EvidenceStatus.CONFIRMED&&value.tags().containsAll(original.tags()),"metadata source anchor mismatch");}
    }
    static void cancel(StoreProjection projection,DurableMetadataCancellation cancellation){
        var transfer=cancellation.transfer();var id=identity(transfer);var active=projection.metadataSources.get(transfer.sourceNamespace());
        require(active==null||!identity(active.transfer()).equals(id),"cannot cancel active metadata generation");
        if(projection.metadataCancelled.contains(id))return;
        var stage=projection.metadataStaging.get(transfer.sourceNamespace());
        require(stage!=null&&stage.transfer().equals(transfer),"metadata cancellation unknown proposal");
        projection.metadataCancelled.add(id);
        // Only the hot pending slot is released; WAL rows, blob references and the
        // immutable cancellation identity remain available after cold recovery.
        projection.metadataStaging.remove(transfer.sourceNamespace());
    }

    /** Upgrade ownership indexes only from the existing authenticated native J04 fact.
     * This preserves old UUID/external-ID reservations and revisions during migration. */
    static void legacy(StoreProjection projection,DirectorFact fact,WorldId world){
        if(preparedClock(projection,fact,world))return;
        if(!fact.factType().equals(new FactType("proutlost_director:location_import_applied")))return;
        require(fact.durable()&&fact.evidenceStatus()==EvidenceStatus.CONFIRMED&&fact.eventId().isEmpty()
            &&fact.subjectRefs().contains(new ScopeRef(ScopeKind.WORLD,world.value())),"legacy metadata fact authority");
        var payload=fact.payload();
        java.util.function.Function<String,String> text=key->((JsonValue.Text)Objects.requireNonNull(payload.get(key),key)).value();
        byte[] raw=Base64.getDecoder().decode(text.apply("location_import_base64"));
        require(raw.length<=32768,"legacy metadata source byte bound");
        var value=SOURCE.decode(raw);
        require(value.worldId().equals(world)&&value.exportDigest().equals(LocationTransferContract.digest(value))
            &&value.sourceNamespace().value().equals(text.apply("source_namespace"))
            &&Long.toString(value.exportRevision().value()).equals(text.apply("export_revision"))
            &&value.exportDigest().value().equals(text.apply("export_digest")),"legacy metadata source identity");
        var expected=new FactId(UUID.nameUUIDFromBytes(("director-location-import-v1/"+world.value()+"/"
            +value.sourceNamespace().value()+"/"+value.exportRevision().value()+"/"+value.exportDigest().value())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        require(fact.factId().equals(expected),"legacy metadata fact id");
        var active=projection.metadataSources.get(value.sourceNamespace());
        require(active==null||active.transfer().exportRevision().compareTo(value.exportRevision())>=0,"legacy writer after metadata generation");
        var old=projection.metadataLegacySources.get(value.sourceNamespace());
        require(old==null||value.exportRevision().compareTo(old.exportRevision())>0
            ||value.exportRevision().equals(old.exportRevision())&&value.exportDigest().equals(old.exportDigest()),"legacy metadata revision regression/conflict");
        require(sources(projection).contains(value.sourceNamespace())||sources(projection).size()<32,"legacy metadata source capacity");
        java.util.function.Consumer<AggregateKey> reserve=key->{
            var owner=projection.metadataOwners.putIfAbsent(key,value.sourceNamespace());
            require(owner==null||owner.equals(value.sourceNamespace()),"legacy metadata cross-source identity");
        };
        for(var row:value.regions()){reserve.accept(new AggregateKey(AggregateKind.REGION,row.regionId().value()));reserve.accept(new AggregateKey(AggregateKind.REGION_MEMORY,row.regionId().value()));}
        for(var row:value.anchors())reserve.accept(new AggregateKey(AggregateKind.ANCHOR,row.anchorId().value()));
        for(var row:value.locations()){
            reserve.accept(new AggregateKey(AggregateKind.LOCATION,row.locationId().value()));
            var identity=new LocationIdentity(value.sourceNamespace(),row.externalId());
            var previous=projection.metadataLocationIds.putIfAbsent(identity,row.locationId());
            var external=projection.metadataExternalIds.putIfAbsent(row.locationId(),identity);
            require(previous==null||previous.equals(row.locationId()),"legacy external location id changed");
            require(external==null||external.equals(identity),"legacy location external id changed");
        }
        projection.metadataLegacySources.put(value.sourceNamespace(),value);
        projection.metadataLegacyManifests.put(value.sourceNamespace(),LocationTransferContract.prepare(value).manifest());
    }

    static RestoredAggregate hydrate(Head head,AggregateKey key,Reader reader)throws IOException{
        var page=PAGE.decode(reader.read(head.page()).bytes());
        require(page.generation().equals(head.generation())&&page.sourceNamespace().equals(head.source())
            &&head.ordinal()>=0&&head.ordinal()<page.states().size(),"metadata head page binding");
        var state=page.states().get(head.ordinal());
        require(state.key().equals(key)&&state.header().revision().equals(head.revision()),"metadata head revision/key");
        return new RestoredAggregate(state,head.sequence(),head.transactionHash());
    }
}
