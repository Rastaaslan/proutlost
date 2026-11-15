package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import fr.proutlost.director.runtime.ResultTransaction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/**
 * Actor-owned native J01 campfire anchor -> J04 WAL -> MemoryView.
 * Anchors originate at a real NeoForge BlockEvent, not a chunk heuristic.
 * Versions queue in recorded order. Acknowledgement is withheld until
 * actual fsync AND projection of the matching aggregate, including reboot.
 *
 * This laboratory bridge is NOT the PRODUCTION anchor provider; no gameplay
 * rights or region identification are conferred by placement alone.
 */
public final class DirectorNativeAnchorWalIngestion implements AutoCloseable {
    public enum Phase { IDLE, WAL_PENDING, PROJECTION_PENDING, FAILED_SAFE, CLOSED }
    public record Status(Phase phase,long durableAnchors,long durableSequence,
                         boolean gameplay) {}
    private final MinecraftServer server;
    private final WorldId world;
    private final DurableStore writer;
    private final StoreSession session;
    private final DirectorNativeObservationQualification observation;
    private final DirectorNativeMemoryProjectionQualification memory;
    private Phase phase=Phase.IDLE;
    private AnchorRecord pendingRecord;
    private DurableBatch pendingBatch;
    private CompletableFuture<CommitAck> pendingAck;
    private long durableAnchors, durableSequence;

    public DirectorNativeAnchorWalIngestion(MinecraftServer server,WorldId world,
            DurableStore writer,DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory) {
        this(server,world,writer,observation,memory,ProviderMode.TEST_ONLY);
    }
    public DirectorNativeAnchorWalIngestion(MinecraftServer server,WorldId world,
            DurableStore writer,DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory,ProviderMode mode) {
        this.server=Objects.requireNonNull(server);
        this.world=Objects.requireNonNull(world);
        this.writer=Objects.requireNonNull(writer);
        this.observation=Objects.requireNonNull(observation);
        this.memory=Objects.requireNonNull(memory);
        actor();
        session=writer.session();
        if(!world.equals(session.worldId()) || writer instanceof DirectorLabReadOnlyStore
           || observation.mode()!=mode||memory.mode()!=mode
           || observation.configuration().calibration()!=(mode==ProviderMode.PRODUCTION?Calibration.APPROVED:Calibration.DEFAULT_FOR_TESTING))
            throw new IllegalArgumentException("unqualified native anchor WAL");
    }
    private void actor(){
        if(!server.isSameThread())
            throw new IllegalStateException("anchor WAL server actor");
    }
    private void fail(String code){
        phase=Phase.FAILED_SAFE;
        System.out.println("DIRECTOR_NATIVE_J01_ANCHOR_WAL_FAILED_SAFE "+code);
    }
    public void poll(){poll(true);}
    public void poll(boolean allowNewWrite){
        actor();
        if(phase==Phase.CLOSED || phase==Phase.FAILED_SAFE)return;
        try {
            if(!session.equals(writer.session())){
                fail("J04_SESSION_CHANGED");return;
            }
            if(phase==Phase.WAL_PENDING){
                if(pendingAck==null || !pendingAck.isDone())return;
                if(pendingAck.isCompletedExceptionally()){
                    fail("ANCHOR_WAL_IO_FAILED");return;
                }
                var ack=pendingAck.getNow(null);
                if(ack==null || ack.ackStatus()!=AckStatus.DURABLE ||
                    !ack.storeGeneration().equals(session.storeGeneration()) ||
                    !ack.transactionId().equals(pendingBatch.transactionId()) ||
                    !ack.transactionHash().equals(pendingBatch.transactionHash())){
                    fail("ANCHOR_DURABLE_ACK_REJECTED");return;
                }
                durableSequence=ack.durableSequence().value();
                phase=Phase.PROJECTION_PENDING;
                return;
            }
            if(phase==Phase.PROJECTION_PENDING){
                var status=memory.status();
                if(status.failClosed()){
                    fail("J04_ANCHOR_PROJECTION_FAILED");return;
                }
                if(!status.complete() || status.lastDurableSequence()<durableSequence)return;
                var recovered=memory.view().orElseThrow().anchors().get(
                    pendingRecord.anchorId());
                if(!pendingRecord.equals(recovered)){
                    fail("J04_ANCHOR_WAL_READBACK_MISMATCH");return;
                }
                observation.acknowledgeAnchor(pendingRecord.anchorId(),
                    pendingRecord.header().revision());
                durableAnchors=Math.addExact(durableAnchors,1);
                System.out.println("DIRECTOR_NATIVE_J01_ANCHOR_DURABLE"+
                    " count="+durableAnchors+
                    " revision="+pendingRecord.header().revision().value()+
                    " active="+pendingRecord.active()+" gameplay=false");
                pendingRecord=null;pendingBatch=null;pendingAck=null;
                phase=Phase.IDLE;
                return;
            }
            if(!allowNewWrite)return;
            if(memory.view().isEmpty())return;
            if(observation.anchorOwner().traceBusy())return;
            var candidate=observation.nextUncommittedAnchor();
            if(candidate.isEmpty())return;
            var anchor=candidate.orElseThrow();
            if(!anchor.dimension().equals(anchor.position().dimension()) ||
                anchor.origin()!=AnchorOrigin.CAMP)
                throw new IllegalArgumentException("invalid anchor source");
            var before=memory.view().orElseThrow().anchors().get(anchor.anchorId());
            if(before==null && anchor.header().revision().value()!=1)
                throw new IllegalArgumentException("unknown original anchor");
            if(before!=null && !anchor.header().revision().equals(
                    before.header().revision().plus(1)))
                throw new IllegalArgumentException("anchor revision conflict");
            var txId=UUID.nameUUIDFromBytes(
                ("director-j01-anchor-v1/"+world.value()+"/"+
                    anchor.anchorId().value()+"/"+anchor.header().revision().value())
                    .getBytes(StandardCharsets.UTF_8));
            pendingRecord=anchor;
            pendingBatch=ResultTransaction.batch(new TransactionId(txId),
                session.storeGeneration(),Map.of(),
                List.of(new DurableAggregate(new AnchorAggregate(anchor))),
                List.of());
            pendingAck=writer.append(pendingBatch).toCompletableFuture();
            phase=Phase.WAL_PENDING;
        }catch(RuntimeException fault) {
            fail(fault.getClass().getSimpleName());
        }
    }
    public Status status(){
        actor();
        return new Status(phase,durableAnchors,durableSequence,false);
    }
    @Override public void close(){
        actor();phase=Phase.CLOSED;
    }
}
