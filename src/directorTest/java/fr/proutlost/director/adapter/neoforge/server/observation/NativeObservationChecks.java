package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.concurrent.*;

/** Native assertion harness for Work's isolated dedicated-server fixture. NOT_RUN in CB-203.
 * Call after actual lifecycle/player actions, not after posting synthetic events.
 * It checks server observations only; it is not evidence of client rendering or network delivery.
 */
public final class NativeObservationChecks {
    private NativeObservationChecks() {}
    public static void connectedPlayer(MinecraftServer server,NeoForgeObservation adapter,
                                       SampleRequest request,ServerPlayer actual) {
        if(!server.isSameThread())throw new AssertionError("fixture must run on server actor");
        SnapshotBatch batch=adapter.sample(request);PlayerSnapshot player=batch.context().players().get(new PlayerId(actual.getUUID()));
        if(player==null)throw new AssertionError("connected requested player missing");
        var pos=actual.blockPosition();BlockPosition captured=player.position().position();
        if(captured.x()!=pos.getX()||captured.y()!=pos.getY()||captured.z()!=pos.getZ()
                ||!captured.dimension().value().equals(actual.serverLevel().dimension().location().toString()))
            throw new AssertionError("snapshot differs from live server position");
        try{batch.context().players().clear();throw new AssertionError("mutable snapshot");}
        catch(UnsupportedOperationException expected){ /* Required immutable DTO. */ }
        if(actual.serverLevel().hasChunkAt(actual.blockPosition())&&!batch.loadedChunks().contains(new ChunkRef(captured.dimension(),Math.floorDiv(captured.x(),16),Math.floorDiv(captured.z(),16))))
            throw new AssertionError("already-loaded player chunk identity missing");
        if(request.sensorSet().contains(SensorKind.TERRAIN)&&!batch.missingData().contains(SensorKind.TERRAIN))throw new AssertionError("chunk identity is not terrain safety evidence");
    }
    public static void afterDeath(NeoForgeObservation adapter,SampleRequest request,PlayerId id) {
        var player=adapter.sample(request).context().players().get(id);
        if(player==null||player.eligibility()==PlayerEligibility.ELIGIBLE)throw new AssertionError("dead/recovering player admitted");
    }
    public static void afterDimensionChange(NeoForgeObservation adapter,SampleRequest request,PlayerId id,DimensionId destination) {
        var player=adapter.sample(request).context().players().get(id);
        if(player==null||!player.position().position().dimension().equals(destination)||player.eligibility()==PlayerEligibility.ELIGIBLE)
            throw new AssertionError("transition safety or dimension invalidation missing");
    }
    /** Return a completion to the harness mailbox; never join/get on the server actor. */
    public static CompletionStage<Void> workerDenied(NeoForgeObservation adapter,SampleRequest request,Executor worker) {
        return CompletableFuture.runAsync(()->{
            try{adapter.sample(request);throw new AssertionError("worker accessed live Minecraft");}
            catch(IllegalStateException expected){ /* Thread ownership enforced. */ }
        },worker);
    }
}
