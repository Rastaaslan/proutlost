package fr.proutlost.director.runtime;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Builds one result/facts/outbox/memory transaction. No ACK means no published outcome.
 * Memory IDs are derived from actual instance/player/role identities, never synthetic event IDs.
 */
public final class ResultTransaction {
    private ResultTransaction() {}
    public record Participant(PlayerId player, String role, PerceptionStatus perception,
                              Optional<RegionId> region, Optional<LocationId> location,
                              Set<FactId> consequences, Set<ObservableReaction> reactions) {
        public Participant {
            Objects.requireNonNull(player); Objects.requireNonNull(role); Objects.requireNonNull(perception);
            Objects.requireNonNull(region); Objects.requireNonNull(location);
            consequences=Set.copyOf(consequences); reactions=Set.copyOf(reactions);
        }
    }
    public static DurableBatch confirmed(WorldId world, Epoch generation, TransactionId transaction,
                                         EventInstance event, ResultRecord result, List<Participant> participants,
                                         List<DurableRecord> stateChanges, List<BlobContent> blobs,
                                         Map<ScopeRef,Revision> revisions, Tick tick) {
        if (result.result().outcome()!=Outcome.SUCCEEDED && result.result().outcome()!=Outcome.PARTIAL
                && result.result().outcome()!=Outcome.RECONCILED) throw new IllegalArgumentException("unconfirmed outcome");
        if(event.state()!=EventState.RESOLVING || !event.header().id().equals(event.instanceId().value()) || tick.compareTo(event.header().updatedTick())<0)
            throw new IllegalArgumentException("event must be resolving with consistent identity/time");
        Map<FactId, FactEvidence> evidence=new HashMap<>();
        for(var e:result.result().factEvidence()) {
            if(evidence.put(e.factId(),e)!=null) throw new IllegalArgumentException("duplicate evidence");
        }
        Set<FactId> confirmed=new HashSet<>();
        for(var fact:result.facts()) {
            var proof=evidence.get(fact.factId());
            if(!fact.eventId().equals(Optional.of(event.instanceId())) || fact.evidenceStatus()!=EvidenceStatus.CONFIRMED
                    || proof==null || proof.status()!=EvidenceStatus.CONFIRMED || !confirmed.add(fact.factId()))
                throw new IllegalArgumentException("fact is not confirmed for this event");
        }
        if(result.outbox().size()!=result.facts().size() || result.outbox().stream().map(DirectorEnvelope::messageId).distinct().count()!=result.outbox().size())
            throw new IllegalArgumentException("one outbox message per confirmed fact required");
        Set<FactId> published=new HashSet<>();
        for(var envelope:result.outbox()) {
            if(!envelope.worldId().equals(world) || !(envelope.payload() instanceof FactPayload payload)
                    || !result.facts().contains(payload.value()) || !published.add(payload.value().factId())) throw new IllegalArgumentException("outbox fact mismatch");
        }
        BlobContent outcome=BlobContent.capture(new ResourceId("proutlost_director:confirmed_result"),1,
                new ModelCodec<>(ResultRecord.class).encode(result));
        List<DurableRecord> records=new ArrayList<>(); records.add(new DurableResult(result));
        var terminal=new EventInstance(new AggregateHeader(1,event.header().id(),event.header().revision().plus(1),event.header().lastSequence(),tick),
                event.instanceId(),event.definition(),event.catalogGeneration(),result.result().outcome()==Outcome.PARTIAL?EventState.PARTIAL:EventState.SUCCEEDED,
                "result_confirmed",event.roleAssignments(),event.regionIds(),event.contextRef(),event.stepStates(),event.reservationRefs(),event.effectRefs(),event.mutationRefs(),
                event.chainId(),event.requirementIds(),event.startedTick(),event.elapsedActiveTicks(),event.maxDurationTicks(),event.recoveryPolicy(),Optional.of(result.result().outcome()));
        records.add(new DurableAggregate(new EventAggregate(terminal)));
        for(var change:stateChanges) {
            if(change instanceof DurableResult || change instanceof DurablePrepare || change instanceof DurableFact
                    || change instanceof DurableOutbox || change instanceof DurableAggregate a && (a.state() instanceof PlayerSignificantAggregate || a.state() instanceof EventAggregate))
                throw new IllegalArgumentException("result-owned record supplied twice");
            records.add(change);
        }
        result.facts().forEach(f->records.add(new DurableFact(f)));
        result.outbox().forEach(f->records.add(new DurableOutbox(f)));
        Set<UUID> memories=new HashSet<>();
        for(Participant p:participants) {
            if(!event.roleAssignments().roles().getOrDefault(p.role(),List.of()).contains(p.player())
                    || !confirmed.containsAll(p.consequences()) || p.consequences().isEmpty())
                throw new IllegalArgumentException("participant lacks a confirmed event consequence");
            UUID id=UUID.nameUUIDFromBytes(("director-memory-v1\0"+world.value()+"\0"+event.instanceId().value()
                    +"\0"+p.player().value()+"\0"+p.role()).getBytes(StandardCharsets.UTF_8));
            if(!memories.add(id)) throw new IllegalArgumentException("duplicate participant role");
            // RECEIVED means technical receipt only. It never creates evidence or a reaction.
            var memory=new PlayerSignificantMemory(new AggregateHeader(1,id,new Revision(1),new Sequence(0),tick),
                    new MemoryId(id),p.player(),event.instanceId(),event.definition().id(),p.role(),p.perception(),
                    outcome.reference(),p.region(),p.location(),p.consequences(),p.reactions(),tick);
            records.add(new DurableAggregate(new PlayerSignificantAggregate(memory)));
        }
        List<BlobContent> contents=new ArrayList<>(blobs); if(!contents.contains(outcome)) contents.add(outcome);
        var expected=new HashMap<>(revisions);var eventScope=new ScopeRef(ScopeKind.EVENT,event.instanceId().value());
        Revision supplied=expected.putIfAbsent(eventScope,event.header().revision());
        if(supplied!=null&&!supplied.equals(event.header().revision()))throw new IllegalArgumentException("event revision mismatch");
        return batch(transaction,generation,expected,records,contents);
    }
    public static DurableBatch batch(TransactionId id, Epoch generation, Map<ScopeRef,Revision> revisions,
                                      List<DurableRecord> records,List<BlobContent> blobs) {
        var unsigned=new DurableBatch(id,generation,revisions,records,blobs,Optional.empty(),new Fingerprint("0".repeat(64)));
        // Preserve the existing producer hash for every small batch. Large blobs
        // live as raw J04 files; encoding their bytes as a G0 JSON Text would
        // reject valid content above 48 KiB before append/force can even run.
        boolean largeBlob=blobs.stream().anyMatch(b->b.reference().bytes()>49152);
        var hash=!largeBlob
                ?BlobContent.hash(new ModelCodec<>(DurableBatch.class).encode(unsigned))
                :referencedBlobHash(unsigned);
        return new DurableBatch(id,generation,revisions,records,blobs,Optional.empty(),hash);
    }
    private static Fingerprint referencedBlobHash(DurableBatch batch) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update("director-result-blobrefs-v1\0".getBytes(StandardCharsets.UTF_8));
            var header=new DurableBatch(batch.transactionId(),batch.expectedStoreGeneration(),
                    batch.expectedAggregateRevisions(),List.of(),List.of(),batch.permitAttempt(),batch.transactionHash());
            hashPart(digest,new ModelCodec<>(DurableBatch.class).encode(header));
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(batch.records().size()).array());
            var recordCodec=new ModelCodec<>(DurableRecord.class);
            for(var record:batch.records())hashPart(digest,recordCodec.encode(record));
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(batch.blobs().size()).array());
            var referenceCodec=new ModelCodec<>(BlobRef.class);
            // BlobContent validates SHA-256, byte length, codec and version.
            // Ordered references therefore bind the exact original bytes without
            // changing the public G0 codec or J04 raw blob/WAL representation.
            for(var blob:batch.blobs())hashPart(digest,referenceCodec.encode(blob.reference()));
            return new Fingerprint(HexFormat.of().formatHex(digest.digest()));
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static void hashPart(java.security.MessageDigest digest,byte[] bytes) {
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
