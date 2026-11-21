package fr.proutlost.worldprep.physical;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.MetadataReadPort;
import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.storage.LevelResource;

/** Explicit TEST_ONLY readback. Reuses the actual running J04 owner and actual
 * selected game readers; no import, writer open, teleport, save or player creation. */
public final class NativeObservedGeometryReadback {
    private static final ExecutorService IO=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),
        task->{var thread=new Thread(task,"worldprep-observed-readback");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private static final Set<net.minecraft.server.MinecraftServer> ACTIVE=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ModelCodec<LocationImport> CODEC=new ModelCodec<>(LocationImport.class);
    private record Expected(NativeObservedPhysicalDirectorExport.Receipt receipt,LocationImport source,List<AggregateKey> keys){}
    public record Result(UUID world,UUID survey,long bootEpoch,long cut,long selectedMemoryCut,String generation,String sourceSha256,
            int durableHeads,UUID selectedRegion,UUID selectedLocation,int selectedMetadata,String status){}
    private NativeObservedGeometryReadback(){}
    private static void require(boolean yes,String why){if(!yes)throw new IllegalArgumentException(why);}
    public static CompletionStage<Result> verifyAsync(ServerLevel level,ServerPlayer player,UUID survey){
        var server=level.getServer();require(server.isSameThread()&&Boolean.getBoolean("proutlost.worldprep.observedGeometryQa")
            &&Boolean.getBoolean("proutlost.director.nativeLab")&&player!=null&&player.getServer()==server&&player.hasPermissions(4),"explicit geometry QA flag and actual connected OP4 player required");
        var life=DirectorStandaloneMod.directorLifecycle();var store=life.runtimePerceptionStore(server).orElseThrow();
        MetadataReadPort port=store.metadataReads();var snapshot=port.snapshot().orElseThrow();Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        synchronized(ACTIVE){require(ACTIVE.add(server),"one geometry QA readback per actual world owner");}
        try{
        CompletionStage<Result> work=CompletableFuture.supplyAsync(()->load(world,survey),IO).thenCompose(expected->{
            require(expected.source().worldId().equals(snapshot.session().worldId()),"projection receipt belongs to another Director world");
            var identity=LocationTransferContract.identity(LocationTransferContract.prepare(expected.source()).manifest());
            require(identity.equals(snapshot.generations().get(expected.source().sourceNamespace())),"expected geometry source is not current J04 generation");
            return port.readMetadataSource(snapshot,expected.source().sourceNamespace()).thenCompose(actual->{
                require(actual.filter(expected.source()::equals).isPresent(),"actual J04 raw source differs from producer archive");
                return heads(port,snapshot,expected,0).thenCompose(count->{
                    var result=new CompletableFuture<Result>();
                    try{server.execute(()->{try{
                        require(port.owns(snapshot)&&port.session().equals(snapshot.session())&&!port.readOnlySafe(),"QA readback authority/session revoked");
                        var current=port.snapshot().orElseThrow();require(current.generations().equals(snapshot.generations()),"source generation changed during QA readback");
                        var actualPlayer=server.getPlayerList().getPlayer(player.getUUID());require(actualPlayer==player&&player.hasPermissions(4),"original real OP4 session disconnected/changed");
                        var coverage=life.runtimeRegionAnchorCoverage(server).orElseThrow();
                        require(coverage.current(port,current),"actual selected context is not current for this source generation and heads");
                        var selected=life.runtimeMemoryView(server).orElseThrow();var view=life.memoryProjectionView(server).orElseThrow();
                        require(view.complete()&&!view.failClosed(),"actual selected memory is not ready");
                        int metadata=selected.regionRecords().size()+selected.regions().size()+selected.locations().size()+selected.anchors().size();
                        require(metadata<=512&&!selected.scopes().scopes().contains(new ScopeRef(ScopeKind.WORLD,snapshot.session().worldId().value())),"selected metadata must remain bounded and scoped");
                        var pos=player.blockPosition();var point=new BlockPosition(new DimensionId(player.serverLevel().dimension().location().toString()),pos.getX(),pos.getY(),pos.getZ());
                        var primary=life.runtimePrimaryRegion(server,point).orElseThrow();
                        var region=expected.source().regions().stream().filter(value->value.regionId().equals(primary)&&contains(value.dimension(),value.bounds(),point)).findFirst().orElseThrow();
                        var location=expected.source().locations().stream().filter(value->value.regionIds().contains(primary)&&contains(value.dimension(),value.bounds(),point)).findFirst().orElseThrow();
                        require(selected.regionRecords().containsKey(primary)&&selected.regions().stream().anyMatch(value->value.regionId().equals(primary))&&selected.locations().containsKey(location.locationId()),"actual game readers have not selected geometry region/memory/location for real player");
                        require(selected.regionRecords().get(primary).status()==RegionStatus.ACTIVE&&selected.regionRecords().get(primary).supportShape().boxes().contains(box(region.dimension(),region.bounds()))
                            &&selected.locations().get(location.locationId()).bounds().equals(box(location.dimension(),location.bounds()))
                            &&selected.locations().get(location.locationId()).sourceNamespace().equals(expected.source().sourceNamespace())
                            &&selected.locations().get(location.locationId()).importRevision().equals(expected.source().exportRevision()),"actual selected geometry differs from source");
                        result.complete(new Result(snapshot.session().worldId().value(),survey,snapshot.session().bootEpoch().value(),snapshot.cut().value(),view.lastDurableSequence(),identity.value(),expected.receipt().g0Sha256(),count,
                            primary.value(),location.locationId().value(),metadata,"QA_NATIVE_GEOMETRY_J04_AND_SELECTED_READERS_PASS"));
                    }catch(RuntimeException failure){result.completeExceptionally(failure);}});}catch(RuntimeException failure){result.completeExceptionally(failure);}
                    return result;
                });
            });
        });
        return work.whenComplete((value,error)->{synchronized(ACTIVE){ACTIVE.remove(server);}});
        }catch(RuntimeException failure){synchronized(ACTIVE){ACTIVE.remove(server);}throw failure;}
    }
    private static Expected load(Path world,UUID survey){
        try{
            var proof=PhysicalStore.openReadOnly(world.resolve("worldprep-surveys/exports").resolve(survey.toString()).resolve("g0-projection"));
            var receipt=proof.read("receipt",NativeObservedPhysicalDirectorExport.Receipt.class);
            require(receipt.version()==1&&receipt.survey().equals(survey)&&receipt.g0Revision()>0&&receipt.g0Sha256().matches("[0-9a-f]{64}")
                &&receipt.g0Digest().matches("[0-9a-f]{64}")&&receipt.status().equals("GEOMETRY_G0_TRANSPORT_READY_NOT_IMPORTED")
                &&receipt.mappingSha256().equals(PhysicalStore.sha(proof.readText("mapping"))),"QA producer receipt/mapping mismatch");
            Path archive=world.resolve("director-location-imports").resolve("observed-physical-r"+receipt.g0Revision()+"-"+receipt.g0Sha256()+".g0");
            for(var path=archive;path!=null;path=path.getParent())require(!Files.isSymbolicLink(path),"symlinked QA source archive");
            require(Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)&&Files.size(archive)<=1_048_576,"bounded existing QA source archive required");
            byte[] bytes;try(var in=Files.newInputStream(archive)){bytes=in.readNBytes(1_048_577);}require(bytes.length<=1_048_576&&BlobContent.hash(bytes).value().equals(receipt.g0Sha256()),"QA source archive SHA mismatch");
            var source=CODEC.decode(bytes);require(Arrays.equals(bytes,CODEC.encode(source))&&source.worldId().value().equals(receipt.directorWorld())&&source.exportRevision().value()==receipt.g0Revision()
                &&source.exportDigest().value().equals(receipt.g0Digest())&&source.anchors().isEmpty()
                &&source.sourceNamespace().equals(fr.proutlost.worldprep.director.ObservedPhysicalG0Projection.SOURCE_NAMESPACE)
                &&source.regions().size()==receipt.regions()&&source.locations().size()==receipt.locations()&&receipt.anchors()==0
                &&source.regions().stream().allMatch(value->value.tags().isEmpty())&&source.locations().stream().allMatch(value->value.tags().isEmpty()),"QA source canonical identity differs");
            var keys=new ArrayList<AggregateKey>();for(var region:source.regions()){keys.add(new AggregateKey(AggregateKind.REGION,region.regionId().value()));keys.add(new AggregateKey(AggregateKind.REGION_MEMORY,region.regionId().value()));}
            for(var location:source.locations())keys.add(new AggregateKey(AggregateKind.LOCATION,location.locationId().value()));
            require(keys.size()<=768,"observed geometry readback key capacity");return new Expected(receipt,source,List.copyOf(keys));
        }catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private static CompletionStage<Integer> heads(MetadataReadPort port,MetadataReadSnapshot snapshot,Expected expected,int offset){
        if(offset==expected.keys().size())return CompletableFuture.completedFuture(offset);
        var requested=Set.copyOf(expected.keys().subList(offset,Math.min(offset+96,expected.keys().size())));
        var query=new StoreQuery(expected.source().worldId(),requested,new Sequence(0),Optional.empty(),96);
        return port.readMetadata(snapshot,query).thenCompose(read->{
            ReadContracts.validate(read,query,snapshot.session());require(read.status()==ReadStatus.AVAILABLE,"QA geometry head page unavailable");
            var page=read.page().orElseThrow();require(page.records().isEmpty()&&!page.hasMore()&&page.aggregates().size()==requested.size()&&page.lastDurableSequence().equals(snapshot.cut()),"QA geometry head page cut/count differs");
            Set<AggregateKey> missing=new HashSet<>(requested);
            for(var restored:page.aggregates()){
                var state=restored.state();require(missing.remove(state.key())&&port.metadataOwner(snapshot,state.key()).filter(expected.source().sourceNamespace()::equals).isPresent(),"QA geometry head owner/identity differs");
                if(state instanceof RegionAggregate region){var input=expected.source().regions().stream().filter(value->value.regionId().equals(region.value().regionId())).findFirst().orElseThrow();
                    require(region.value().status()==RegionStatus.ACTIVE&&region.value().supportShape().boxes().contains(box(input.dimension(),input.bounds()))&&region.value().anchorIds().isEmpty(),"QA durable region geometry differs");}
                else if(state instanceof LocationAggregate location){var input=expected.source().locations().stream().filter(value->value.locationId().equals(location.value().locationId())).findFirst().orElseThrow();
                    require(location.value().active()&&location.value().bounds().equals(box(input.dimension(),input.bounds()))&&location.value().externalId().equals(input.externalId())
                        &&location.value().tags().isEmpty()&&location.value().anchorIds().isEmpty()&&location.value().importRevision().equals(expected.source().exportRevision()),"QA durable location metadata differs");}
                else require(state instanceof RegionMemoryAggregate,"foreign QA geometry head type");
            }
            require(missing.isEmpty(),"QA geometry head missing");return heads(port,snapshot,expected,offset+requested.size());
        });
    }
    private static Aabb box(DimensionId dimension,ImportBounds b){return new Aabb(dimension,b.minX(),b.minY(),b.minZ(),b.maxXExclusive(),b.maxYExclusive(),b.maxZExclusive());}
    private static boolean contains(DimensionId dimension,ImportBounds b,BlockPosition p){return dimension.equals(p.dimension())&&p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()&&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();}
}
