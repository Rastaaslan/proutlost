package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.model.BlobContent;
import fr.proutlost.director.api.model.Fingerprint;
import fr.proutlost.director.api.model.PhysicalCatalogFacts;
import fr.proutlost.director.api.model.PhysicalCatalogModels;
import fr.proutlost.director.api.model.PhysicalCatalogSourceDescriptor;
import fr.proutlost.director.api.model.ResourceId;
import fr.proutlost.director.bootstrap.PhysicalCatalogIntegrationProvider;
import fr.proutlost.director.api.port.PhysicalCatalogRuntimePort;
import fr.proutlost.worldprep.director.CatalogImportProbe;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Real consumer: never returns producer input as admitted and never writes a receipt itself. */
public final class ContraRealDirectorCatalogImportProbe implements CatalogImportProbe,PhysicalCatalogIntegrationProvider {
    private PhysicalCatalogModels.Key readingKey;private PhysicalCatalogModels.Readback readingStamp;
    private CompletableFuture<CatalogSnapshot> reading;
    // Ephemeral bounded inputs only. J04 + the actual producer files remain authoritative.
    private BlobContent inspectingSource;private CompletableFuture<PhysicalCatalogSourceDescriptor> inspecting;private boolean delivered;
    private CatalogSnapshot actualReadback;
    private static String utf8(byte[] bytes)throws IOException{
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new IOException("Exact physical catalogue UTF-8 required",e);}
    }
    private static CatalogSnapshot decode(byte[] bytes)throws IOException{
        String raw=utf8(bytes);try{var value=StrictCodec.decode(raw,CatalogSnapshot.class);CatalogValidation.requireSealed(value);
            if(value.state()!=Vocabulary.CatalogState.READY||!StrictCodec.encode(value).equals(raw))throw new IOException("Exact sealed READY D0 required");return value;}
        catch(IllegalArgumentException e){throw new IOException("Invalid complete physical catalogue",e);}
    }
    public static Optional<PhysicalCatalogRuntimePort> runtime(MinecraftServer server){
        if(server==null||!server.isSameThread())return Optional.empty();
        try{
            // Optional platform boundary; WorldPrep has no directorPlatform compile dependency.
            var mod=Class.forName("fr.proutlost.director.neoforge.DirectorStandaloneMod");
            Object lifecycle=mod.getMethod("directorLifecycle").invoke(null);
            Object result=lifecycle.getClass().getMethod("physicalCatalogues",MinecraftServer.class).invoke(lifecycle,server);
            @SuppressWarnings("unchecked") var port=(Optional<PhysicalCatalogRuntimePort>)result;return port;
        }catch(ClassNotFoundException missing){return Optional.empty();}
        catch(ReflectiveOperationException invalid){throw new IllegalStateException("Installed Director physical owner boundary incompatible",invalid);}
    }
    private Fingerprint actualClassSha()throws IOException{
        String resource="/"+getClass().getName().replace('.','/')+".class";
        try(var in=getClass().getResourceAsStream(resource)){
            if(in==null)throw new IOException("Actual provider bytecode absent");byte[] bytes=in.readNBytes(262145);
            if(bytes.length>262144)throw new IOException("Provider bytecode bound");return BlobContent.hash(bytes);
        }
    }
    @Override public Optional<CatalogSnapshot> pollImportAndReadAdmitted(String canonicalJson,Id expectedWorld,Id expectedBuild)throws IOException{
        MinecraftServer server=ServerLifecycleHooks.getCurrentServer();
        if(server==null||!server.isSameThread())return Optional.empty();
        byte[] offered=canonicalJson.getBytes(StandardCharsets.UTF_8);if(offered.length<1||offered.length>1048576)throw new IOException("Physical source bound");
        var owner=runtime(server);if(owner.isEmpty())return Optional.empty();var actualOwner=owner.orElseThrow();
        if(actualOwner.failedSafe())throw new IOException("Actual Director physical owner failed safe; retain source and prior durable references");
        if(!actualOwner.ready())return Optional.empty();
        var sourceBlob=BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,offered);
        if(inspectingSource==null||!inspectingSource.reference().equals(sourceBlob.reference())){
            if(inspectingSource!=null&&!delivered)throw new IOException("Full source changed before its durable receipt; previous source retained");
            inspectingSource=sourceBlob;delivered=false;reading=null;actualReadback=null;readingStamp=null;readingKey=null;
            try{inspecting=actualOwner.inspect(sourceBlob).toCompletableFuture();}catch(RuntimeException unavailable){inspecting=CompletableFuture.failedFuture(unavailable);throw new IOException("Bounded physical decoder unavailable",unavailable);}
            return Optional.empty();
        }
        if(!inspecting.isDone())return Optional.empty();final PhysicalCatalogSourceDescriptor description;
        try{description=inspecting.getNow(null);}catch(CompletionException invalid){throw new IOException("Complete physical source inspection refused",invalid);}
        if(description==null||!description.canonicalSha().equals(sourceBlob.reference().hash())||!description.sourceWorld().value().equals(expectedWorld.value())||!description.sourceBuild().value().equals(expectedBuild.value()))throw new IOException("Physical source world/build/digest differs");
        var grant=actualOwner.approved(sourceBlob.reference().hash(),description.sourceWorld(),description.sourceBuild());
        if(grant.isEmpty())return Optional.empty();var handle=grant.orElseThrow();
        var state=handle.submit(sourceBlob);
        if(state.state()==PhysicalCatalogModels.State.PENDING)return Optional.empty();
        if(state.state()!=PhysicalCatalogModels.State.DURABLE)throw new IOException("Director full-D0 admission refused: "+state.reason());
        var stamp=state.readback().orElseThrow();
        if(reading==null||!stamp.equals(readingStamp)){
            readingKey=handle.key();readingStamp=stamp;actualReadback=null;
            final byte[] expected=offered;
            reading=handle.readDecodedSource(stamp,bytes->{try{
                if(!Arrays.equals(bytes,expected))throw new IOException("Actual Director source differs from the complete offered bytes");
                var admitted=decode(bytes);if(!admitted.world_id().equals(expectedWorld)||!admitted.build_id().equals(expectedBuild))throw new IOException("Actual Director source identity differs");return admitted;
            }catch(IOException invalid){throw new CompletionException(invalid);}}).toCompletableFuture();return Optional.empty();
        }
        if(!reading.isDone())return Optional.empty();
        try{actualReadback=reading.getNow(null);}catch(CompletionException failure){throw new IOException("Actual Director source read/decode failed",failure);}
        if(actualReadback==null||!readingKey.equals(handle.key()))throw new IOException("Actual Director admitted D0 readback missing/changed");
        if(!handle.stillApproved(stamp)||!handle.receiptReady(stamp,actualClassSha()))return Optional.empty();
        delivered=true;return Optional.of(actualReadback);
    }
    @Override public CatalogSnapshot importAndReadAdmitted(String json,Id world,Id build)throws IOException{
        return pollImportAndReadAdmitted(json,world,build).orElseThrow(()->new IOException("PENDING: call nonblocking physical importer poll"));
    }
    @Override public PhysicalCatalogSourceDescriptor describe(byte[] bytes){
        try{var source=decode(bytes);return new PhysicalCatalogSourceDescriptor(new ResourceId(source.world_id().value()),new ResourceId(source.build_id().value()),
            source.catalog_revision().value(),new Fingerprint(source.digest().value()),BlobContent.hash(bytes),
            source.regions().stream().map(r->new ResourceId(r.region_id().value().value())).collect(java.util.stream.Collectors.toSet()),
            source.entries().stream().map(e->new ResourceId(e.location_id().value())).collect(java.util.stream.Collectors.toSet()),
            source.entries().stream().flatMap(e->e.anchors().stream().map(a->new PhysicalCatalogSourceDescriptor.AnchorSource(new ResourceId(e.location_id().value()),new ResourceId(a.anchor_id().value())))).toList());}
        catch(IOException invalid){throw new IllegalArgumentException(invalid);}
    }
    @Override public Integration create(Context context,PhysicalCatalogModels.Key source,byte[] authoredAdapters){
        if(!(context.platform() instanceof MinecraftServer server))throw new IllegalArgumentException("Actual Minecraft server platform required");
        Map<Id,Bounds> dimensions=new HashMap<>();context.dimensions().forEach((id,b)->dimensions.put(new Id(id.value()),
            new Bounds(new Id(id.value()),new BlockPos(b.minX(),b.minY(),b.minZ()),new BlockPos(b.maxXExclusive()-1,b.maxYExclusive()-1,b.maxZExclusive()-1))));
        var semantics=D0NativeAdapters.compilerSemantics(authoredAdapters);
        var view=new DurablePhysicalCatalogView(new Id(source.sourceWorld().value()),new Id(source.sourceBuild().value()),context.session(),dimensions,
            semantics);
        var built=D0NativeAdapters.create(context,view,authoredAdapters);
        return new Integration(view,built.resolver());
    }
}
