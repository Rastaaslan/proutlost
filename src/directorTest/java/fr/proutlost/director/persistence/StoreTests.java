package fr.proutlost.director.persistence;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real temporary disk tests; subprocess kills are SIGKILL, not caught Java exceptions. */
public final class StoreTests {
    private static final WorldId WORLD=new WorldId(new UUID(0,206));
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,2_097_152,4_194_304,16_777_216,32,0,128);
    private static int assertions;
    private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    private static <T>T await(CompletionStage<T> f)throws Exception{return f.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static JournalStore open(Path p)throws Exception{return await(JournalStore.open(p,WORLD,OPTIONS));}
    private static void close(JournalStore s)throws Exception{await(s.closeAsync());}
    private static StoreView requiredView(JournalStore s,StoreQuery query){
        var result=s.view(query);
        ReadContracts.validate(result,query,s.session());
        if(result.status()!=ReadStatus.AVAILABLE)
            throw new IllegalStateException("Expected a verified resident page: "+result.status());
        return result.page().orElseThrow();
    }
    private static Subscription requiredSubscription(JournalStore s,ConsumerSpec spec){
        var result=s.subscribe(spec);
        ReadContracts.validate(result,spec,s.session());
        if(result.status()!=ReadStatus.AVAILABLE)
            throw new IllegalStateException("Expected verified subscription: "+result.status());
        return result.page().orElseThrow();
    }
    private static List<DurableRecord> records(JournalStore s){return requiredView(s,new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),1024)).records();}
    private static DirectorFact fact(int i){return new DirectorFact(new FactId(new UUID(1,i)),new FactType("fixture:fact"),List.of(new ScopeRef(ScopeKind.WORLD,WORLD.value())),Optional.empty(),EvidenceStatus.CONFIRMED,Map.of("n",new JsonValue.Text(""+i)),true,Optional.empty());}
    private static DirectorEnvelope message(int i){return new DirectorEnvelope(1,new MessageId(new UUID(2,i)),WORLD,new ResourceId("fixture:producer"),"m"+i,new ScopeRef(ScopeKind.WORLD,WORLD.value()),new CorrelationId(new UUID(3,i)),Optional.empty(),new Sequence(i),Optional.empty(),new Tick(10),PayloadType.DirectorFact,1,new FactPayload(fact(i)));}
    private static DurableBatch batch(int i,List<DurableRecord> records,List<BlobContent> blobs){return new DurableBatch(new TransactionId(new UUID(4,i)),new Epoch(1),Map.of(),records,blobs,Optional.empty(),BlobContent.hash(new byte[]{(byte)i}));}
    private static DurableBatch factBatch(int i){return batch(i,List.of(new DurableFact(fact(i)),new DurableOutbox(message(i))),List.of());}
    private static List<Path> wal(Path p)throws Exception{try(var f=Files.list(p.resolve("wal"))){return f.sorted(Comparator.comparingLong(x->Long.parseLong(x.getFileName().toString().replace(".wdwal","")))).toList();}}
    private static void copy(Path from,Path to)throws Exception{try(var paths=Files.walk(from)){for(Path p:paths.toList()){Path q=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}}
    private static void roundTrip(Path base)throws Exception {
        Path p=base.resolve("roundtrip");JournalStore s=open(p);check(!s.readOnlySafe(),s.diagnostic());
        check(s.supportedModes().equals(Set.of(ProviderMode.TEST_ONLY,ProviderMode.PRODUCTION)),"real IO store declares explicit bimode provenance");
        var firstSession=s.session();CommitAck ack=await(s.append(factBatch(1)));check(ack.ackStatus()==AckStatus.DURABLE,"durable append");
        check(await(s.append(factBatch(1))).equals(ack),"original ACK retry");
        check(await(s.append(batch(1,List.of(new DurableFact(fact(9))),List.of()))).ackStatus()==AckStatus.REJECTED,"transaction hash/content conflict");
        check(records(s).size()==2,"atomic facts/outbox");
        try{open(p);throw new AssertionError("second writer allowed");}catch(ExecutionException e){check(e.getCause() instanceof IOException,"file lock rejects second writer");}
        check(await(s.append(batch(2,List.of(new DurableOutbox(message(2)),new DurableOutbox(message(3))),List.of()))).ackStatus()==AckStatus.DURABLE,"two messages transaction");
        ConsumerId id=new ConsumerId(new UUID(12,1));ConsumerSpec spec=new ConsumerSpec(id,Set.of(),new Sequence(0),2);
        Subscription sub=requiredSubscription(s,spec);check(sub.batch().size()==2&&sub.hasMore(),"bounded subscriber");
        check(sub.batch().get(0).worldSequence().orElseThrow().value()==1&&sub.batch().get(1).worldSequence().orElseThrow().value()==2,"per-message sequence");
        check(await(s.acknowledge(new ConsumerAck(id,Set.of(),new Sequence(2),new Sequence(0)))).status()==AckStatus.REJECTED,"cannot skip IDs");
        Set<MessageId> ids=new HashSet<>();sub.batch().forEach(m->ids.add(m.messageId()));
        check(await(s.acknowledge(new ConsumerAck(id,ids,new Sequence(2),new Sequence(0)))).status()==AckStatus.DURABLE,"durable cursor");
        check(await(s.checkpoint())>1,"checkpoint generation");close(s);
        s=open(p);check(!s.readOnlySafe(),"reboot healthy: "+s.diagnostic());check(s.session().bootEpoch().compareTo(firstSession.bootEpoch())>0,"boot epoch increases");
        check(!s.session().authoritySession().equals(firstSession.authoritySession()),"authority session changes");
        sub=requiredSubscription(s,new ConsumerSpec(id,Set.of(),new Sequence(3),2));check(sub.batch().size()==1&&sub.batch().getFirst().worldSequence().orElseThrow().value()==3,"cursor/outbox survives restart");
        check(await(s.append(factBatch(1))).equals(ack),"original ACK survives reboot");
        check(records(s).size()==4,"exact records after restart");close(s);
        Path restore=base.resolve("backup");copy(p,restore);s=open(restore);check(records(s).size()==4&&!s.readOnlySafe(),"offline full store backup restore");close(s);
        System.out.println("PASS store/locking/outbox/backup-store-only");
    }
    private static void frames(Path base)throws Exception {
        byte[] payload="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);UUID tx=new UUID(0x0102030405060708L,0x1112131415161718L);
        byte[] encoded=WalFrame.encode(WalFrame.Type.BEGIN,17,tx,payload,1024);
        check(encoded.length==46,"44 byte frame overhead");ByteBuffer b=ByteBuffer.wrap(encoded);
        check(b.getInt()==0x57445731&&b.getShort()==1&&b.get()==1&&b.get()==0&&b.getInt()==46&&b.getLong()==17,"big endian golden header");
        check(b.getLong()==tx.getMostSignificantBits()&&b.getLong()==tx.getLeastSignificantBits()&&b.getInt()==2,"UUID/payload length");
        var crc=new java.util.zip.CRC32C();crc.update(encoded,0,42);check(ByteBuffer.wrap(encoded,42,4).getInt()==(int)crc.getValue(),"independent CRC32C");
        Path p=base.resolve("frame");Files.write(p,encoded);
        try(FileChannel c=FileChannel.open(p)){var f=WalFrame.read(c,1024);check(f.sequence()==17&&f.transaction().equals(tx)&&Arrays.equals(f.payload(),payload),"frame decode");}
        for(int i=0;i<encoded.length;i++) {
            byte[] bad=encoded.clone();bad[i]^=1;Files.write(p,bad);
            try(FileChannel c=FileChannel.open(p)){WalFrame.read(c,1024);throw new AssertionError("corruption accepted byte "+i);}catch(IOException expected){assertions++;}
        }
        System.out.println("PASS WDW1 golden and every-byte corruption");
    }
    private record PrepareFixture(DurableBatch batch,PreparedSlice slice,LiveValidationSnapshot live) {}
    private static PrepareFixture prepare(int id,StoreSession session) {
        var blob=BlobContent.capture(new ResourceId("fixture:capture"),1,new byte[]{1,2,3});
        var refs=new EffectBlobs(Set.of(blob.reference()),blob.reference(),blob.reference(),Map.of(),Map.of());
        var read=new ReadSet(Map.of(),Map.of(),Map.of());var footprint=new Footprint(new DimensionId("minecraft:overworld"),Set.of(),Set.of(),List.of());
        var op=new OperationToken(new UUID(8,1));var slice=new PreparedSlice(new SliceId(new UUID(8,id)),op,footprint,read,List.of(),BudgetMath.zero());
        var reservation=new ReservationToken(new ReservationId(new UUID(9,1)),WORLD,new EventInstanceId(new UUID(10,1)),new Epoch(1),new Tick(200),List.of());
        var p=new PrepareRecord(new TransactionId(new UUID(11,id)),op,new Epoch(1),read,new Tick(200),slice,reservation,new ValidationResult(true,List.of(),read),List.of(),Set.of(),List.of(),refs);
        var batch=new DurableBatch(p.transactionId(),session.storeGeneration(),Map.of(),List.of(new DurablePrepare(p)),List.of(blob),Optional.empty(),CommitPermit.Authority.prepareHash(p));
        return new PrepareFixture(batch,slice,new LiveValidationSnapshot(read,Set.of(),HealthMode.NORMAL,new Tick(100),new Epoch(1)));
    }
    private static PermitResult poll(CommitPermit.Authority a,PrepareFixture f)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);PermitResult r;
        do {r=a.poll(f.batch().transactionId(),f.live());if(r.status()!=PermitResult.Status.PENDING)return r;Thread.sleep(5);}while(System.nanoTime()<end);
        throw new AssertionError("permit timeout");
    }
    private static void permits(Path base)throws Exception {
        Path p=base.resolve("permits");var s=open(p);var authority=new CommitPermit.Authority(s,4);var f=prepare(1,s.session());
        authority.submit(f.batch(),f.live());var result=poll(authority,f);check(result.status()==PermitResult.Status.GRANTED,"real fsync permit");
        result.permit().orElseThrow().consume(authority,f.slice(),f.live());
        try{result.permit().orElseThrow().consume(authority,f.slice(),f.live());throw new AssertionError("permit reusable");}catch(IllegalArgumentException expected){assertions++;}
        var second=prepare(2,s.session());authority.submit(second.batch(),second.live());check(poll(authority,second).status()==PermitResult.Status.REJECTED,"duplicate operation claim");
        var outcome=new SliceResult(Outcome.SUCCEEDED,Set.of(f.slice().operationToken()),Map.of(),0,Set.of(),Set.of(),List.of());
        var resultRecord=new DurableResult(new ResultRecord(f.batch().transactionId(),f.slice().operationToken(),outcome,Set.of(),List.of(fact(88)),List.of(message(88),message(89))));
        check(await(s.append(batch(88,List.of(resultRecord),List.of()))).ackStatus()==AckStatus.DURABLE,"result/facts/two outbox messages atomic");
        check(await(s.append(batch(89,List.of(resultRecord),List.of()))).ackStatus()==AckStatus.REJECTED,"operation result cannot commit twice");
        var delivery=requiredSubscription(s,new ConsumerSpec(new ConsumerId(new UUID(88,1)),Set.of(),new Sequence(0),10));
        check(delivery.batch().size()==2 && delivery.batch().getLast().worldSequence().orElseThrow().value()==2,"nested result outbox per-message sequence");
        BlobRef ref=f.batch().blobs().getFirst().reference();check(await(s.readBlobs(new BlobReadRequest(WORLD,new Epoch(1),Set.of(ref)))).status()==AckStatus.DURABLE,"exact blob read");
        close(s);s=open(p);authority=new CommitPermit.Authority(s,4);second=prepare(3,s.session());authority.submit(second.batch(),second.live());check(poll(authority,second).status()==PermitResult.Status.REJECTED,"durable operation claim after reboot");close(s);
        // Referenced blob corruption must isolate the store, not return a reset empty projection.
        Files.write(p.resolve("blobs").resolve(ref.hash().value()+".blob"),new byte[]{1});s=open(p);check(s.readOnlySafe(),"missing/corrupt referenced blob");close(s);
        System.out.println("PASS durable permits, claims and blob recovery");
    }
    private static void corruption(Path base)throws Exception {
        Path source=base.resolve("corrupt-source");var s=open(source);await(s.append(factBatch(1)));await(s.append(factBatch(2)));close(s);
        Path tail=base.resolve("truncated");copy(source,tail);Path last=wal(tail).getLast();long length=Files.size(last);
        try(var c=FileChannel.open(last,StandardOpenOption.WRITE)){c.truncate(length-7);}
        s=open(tail);check(!s.readOnlySafe(),"incomplete tail recoverable "+s.diagnostic());check(records(s).size()==2,"truncated transaction not applied");check(await(s.append(factBatch(3))).ackStatus()==AckStatus.DURABLE,"append after repaired tail");close(s);
        s=open(tail);check(records(s).size()==4&&!s.readOnlySafe(),"tail repair stable on second boot");close(s);
        Path middle=base.resolve("middle");copy(source,middle);last=wal(middle).getLast();byte[] bytes=Files.readAllBytes(last);bytes[bytes.length/2]^=1;Files.write(last,bytes);
        s=open(middle);check(s.readOnlySafe(),"middle corruption read-only");check(await(s.append(factBatch(5))).ackStatus()==AckStatus.UNAVAILABLE,"corruption closes writer");close(s);
        Path checksum=base.resolve("crc-tail");copy(source,checksum);last=wal(checksum).getLast();bytes=Files.readAllBytes(last);bytes[bytes.length-1]^=1;Files.write(last,bytes);
        s=open(checksum);check(s.readOnlySafe(),"complete corrupt tail not silently dropped");close(s);
        Path manifests=base.resolve("manifests");copy(source,manifests);s=open(manifests);await(s.checkpoint());close(s);
        Files.writeString(manifests.resolve("manifest-A.json"),"broken");s=open(manifests);check(!s.readOnlySafe()&&records(s).size()==4,"fallback older manifest");close(s);
        Files.writeString(manifests.resolve("manifest-B.json"),"broken");s=open(manifests);check(s.readOnlySafe(),"both manifests invalid no reset");close(s);
        System.out.println("PASS corruption/truncation/manifest fallback");
    }
    private static void failures(Path base)throws Exception {
        AtomicBoolean armed=new AtomicBoolean();var s=await(JournalStore.open(base.resolve("fsync"),WORLD,OPTIONS,point->{if(armed.get()&&point.equals("BEFORE_WAL_FORCE"))throw new IOException("injected fsync EIO");}));
        StoreSession old=s.session();armed.set(true);check(await(s.append(factBatch(1))).ackStatus()==AckStatus.UNAVAILABLE,"fsync failure never ACK");
        check(s.readOnlySafe()&&!s.session().equals(old),"writer failure invalidates session");check(s.view(new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),1024)).status()==ReadStatus.UNAVAILABLE,"failed fsync cannot report a valid empty page");close(s);
        armed.set(false);s=await(JournalStore.open(base.resolve("enospc"),WORLD,OPTIONS,point->{if(armed.get()&&point.equals("BEFORE_WAL_WRITE"))throw new IOException("injected ENOSPC");}));
        armed.set(true);check(await(s.append(factBatch(2))).ackStatus()==AckStatus.UNAVAILABLE&&s.readOnlySafe(),"ENOSPC admission closed");close(s);
        var low=new StoreOptions(OPTIONS.frameBytes(),OPTIONS.transactionBytes(),OPTIONS.segmentBytes(),OPTIONS.queueBytes(),32,Long.MAX_VALUE,128);
        s=await(JournalStore.open(base.resolve("disk-low"),WORLD,low));check(s.readOnlySafe(),"real free-space guard");close(s);
        System.out.println("PASS injected fsync/ENOSPC and actual free-space guard (not filesystem exhaustion)");
    }
    private static final SnapshotMigration TEST_MIGRATION=new SnapshotMigration() {
        public int sourceSchema(){return 1;} public int targetSchema(){return 2;}
        public JsonValue migrateRecord(JsonValue source){return source;}
        public void validateTarget(JsonValue target){new ModelCodec<>(DurableRecord.class).decode(CanonicalJsonV1.encode(target));}
    };
    private static void inboxAndCheckpoint(Path base)throws Exception {
        Path p=base.resolve("inbox");var s=open(p);
        var id=new ConsumerId(new UUID(20,1));var inbox=new DurableInbox(new InboxRecord(id,new MessageId(new UUID(21,1)),BlobContent.hash(new byte[]{1}),new Sequence(1),new Revision(1)));
        var b=batch(30,List.of(inbox,new DurableFact(fact(30))),List.of());
        check(await(s.append(b)).ackStatus()==AckStatus.DURABLE,"inbox/local effect atomic");
        check(await(s.append(batch(31,List.of(inbox,new DurableFact(fact(31))),List.of()))).ackStatus()==AckStatus.REJECTED,"duplicate inbox prevents second local effect");
        check(records(s).size()==2,"duplicate inbox leaves all state unchanged");
        await(s.checkpoint());await(s.append(factBatch(32)));await(s.checkpoint());close(s);
        // All history is in the latest durable snapshot; WAL copies are redundant at this boundary.
        for(Path f:wal(p))Files.delete(f);
        s=open(p);check(!s.readOnlySafe()&&records(s).size()==4,"checkpoint alone restores exact history/indexes");
        check(await(s.append(batch(33,List.of(inbox,new DurableFact(fact(33))),List.of()))).ackStatus()==AckStatus.REJECTED,"inbox dedupe survives checkpoint recovery");
        await(s.append(factBatch(34)));await(s.checkpoint());close(s);
        s=open(p);check(!s.readOnlySafe()&&records(s).size()==6,"chained checkpoint plus newer WAL");close(s);
        System.out.println("PASS transactional inbox and independent checkpoint recovery");
    }
    private static void independentCursors(Path base)throws Exception {
        Path path=base.resolve("independent-cursors");var s=open(path);
        var id=new ConsumerId(new UUID(220,1));
        var incoming=new DurableInbox(new InboxRecord(id,new MessageId(new UUID(221,1)),BlobContent.hash(new byte[]{1}),new Sequence(1),new Revision(1)));
        check(await(s.append(batch(220,List.of(incoming),List.of()))).ackStatus()==AckStatus.DURABLE,"incoming cursor without outgoing messages");
        var spec=new ConsumerSpec(id,Set.of(),new Sequence(0),10);
        var sub=requiredSubscription(s,spec);
        check(sub.batch().isEmpty()&&sub.nextSequence().value()==1,"incoming position does not advance outgoing cursor");
        check(await(s.acknowledge(new ConsumerAck(id,Set.of(),new Sequence(1),new Sequence(0)))).status()==AckStatus.REJECTED,"cannot acknowledge beyond outgoing sequence");
        check(await(s.acknowledge(new ConsumerAck(id,Set.of(),new Sequence(0),new Sequence(0)))).status()==AckStatus.DURABLE,"empty delivery ACK at outgoing cursor");
        close(s);s=open(path);
        check(await(s.append(factBatch(221))).ackStatus()==AckStatus.DURABLE,"own empty ACK replays writable");
        sub=requiredSubscription(s,spec);
        check(sub.batch().size()==1&&sub.batch().getFirst().worldSequence().orElseThrow().value()==1,"inbox advancement cannot skip first outbox message");
        check(await(s.acknowledge(new ConsumerAck(id,Set.of(),new Sequence(1),new Sequence(0)))).status()==AckStatus.REJECTED,"undelivered ID cannot be skipped");
        var incoming2=new DurableInbox(new InboxRecord(id,new MessageId(new UUID(221,2)),BlobContent.hash(new byte[]{2}),new Sequence(100),new Revision(2)));
        check(await(s.append(batch(222,List.of(incoming2),List.of()))).ackStatus()==AckStatus.DURABLE,"incoming may advance independently during outgoing delivery");
        check(await(s.acknowledge(new ConsumerAck(id,Set.of(sub.batch().getFirst().messageId()),new Sequence(1),new Sequence(0)))).status()==AckStatus.DURABLE,"outgoing ACK unaffected by incoming advance");
        await(s.checkpoint());close(s);s=open(path);
        check(requiredSubscription(s,spec).batch().isEmpty(),"outgoing ACK survives checkpoint restart");
        check(await(s.append(factBatch(223))).ackStatus()==AckStatus.DURABLE,"checkpoint replay remains writable");
        check(requiredSubscription(s,spec).batch().getFirst().worldSequence().orElseThrow().value()==2,"next outgoing sequence remains contiguous");
        var regression=new DurableInbox(new InboxRecord(id,new MessageId(new UUID(221,3)),BlobContent.hash(new byte[]{3}),new Sequence(99),new Revision(3)));
        check(await(s.append(batch(224,List.of(regression),List.of()))).ackStatus()==AckStatus.REJECTED,"incoming monotonic cursor survives restart independently");
        close(s);System.out.println("PASS independent inbox/outbox cursors, empty ACK replay and no skipped deliveries");
    }
    private static DurableRequest policyRequest(DirectorEnvelope envelope, int revision, RequestSchedulingPolicy policy) {
        var request=MessageContracts.request(envelope);var requirement=(DirectorRequirement)((RequestMessage)envelope.payload()).value();
        var state=new DirectorRequestState(new AggregateHeader(1,new UUID(230,1),new Revision(revision),new Sequence(0),new Tick(100)),requirement.requestId(),request.producerId(),request.producerKey(),RequestKind.REQUIRE,requirement.selector(),request.scope(),requirement.targetConstraints(),requirement.fulfillmentMode(),RequestStatus.WAITING_SAFETY,new DurationTicks(0),Optional.empty(),Set.of(),List.of(),policy);
        return new DurableRequest(envelope,new RequestView(request,state,new Revision(revision)),RequestContracts.idempotenceHash(request));
    }
    private static void amendment101(Path base)throws Exception {
        Path path=base.resolve("amendment101");var s=open(path);
        var envelope=DirectorMessageCodec.decode(Files.readAllBytes(Path.of("build/director-input/spec-1.0.0/examples/messages/require.json")));
        var original=policyRequest(envelope,1,RequestSchedulingPolicy.legacyWire());
        check(await(s.append(batch(230,List.of(original),List.of()))).ackStatus()==AckStatus.DURABLE,"1.0.1 original request policy persisted");
        var changed=policyRequest(envelope,2,new RequestSchedulingPolicy(new NarrativePriority(9),PreferenceDecay.LINEAR_TO_TTL,RequestPolicySource.AUTHORIZED_ADAPTER));
        check(await(s.append(batch(231,List.of(changed,new DurableFact(fact(231))),List.of()))).ackStatus()==AckStatus.REJECTED,"changed policy cannot reuse semantic identity");
        var actor=new ActorIdentity(new SessionId(new UUID(232,1)),ActorKind.SERVER,Optional.empty(),Optional.empty(),4,true);
        var command=new AdminCommand(actor.session(),new RequestId(new UUID(233,1)),new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        var before=new AdminSnapshotStamp(WORLD,new Epoch(1),new Revision(1),new Tick(1),new Sequence(1));
        var after=new AdminSnapshotStamp(WORLD,new Epoch(1),new Revision(2),new Tick(2),new Sequence(2));
        var audit=new DurableRuntimeAdminAudit(new RuntimeAdminAudit(command,BlobContent.hash(new ModelCodec<>(AdminCommand.class).encode(command)),actor,before,after,new AdminResult(AdminStatus.SUCCEEDED,command.requestId(),new Revision(2),Optional.empty(),Optional.empty())));
        var hist=new ActivityHistogram(Map.of(),Map.of(),Map.of(),Map.of(),Optional.of(new ActivityTimeCounts(12,java.util.stream.LongStream.range(0,12).boxed().toList())));
        var anchor=new AnchorId(new UUID(235,1));var camp=new CampRoutineMemory(anchor,List.of(),1,new DurationTicks(5),hist,new UnitValue(1));
        var region=new DurableAggregate(new RegionMemoryAggregate(new RegionMemory(new AggregateHeader(1,new UUID(235,2),new Revision(1),new Sequence(0),new Tick(1)),new RegionId(new UUID(235,2)),Map.of(),new Tick(1),new Tick(1),Set.of(),Set.of(),new RegionActivityState(Map.of(anchor,camp),Map.of()))));
        var auditBatch=batch(232,List.of(audit,region,new DurableFact(fact(232)),new DurableOutbox(message(232))),List.of());
        check(await(s.append(auditBatch)).ackStatus()==AckStatus.DURABLE,"new audit union with atomic fact/outbox");
        check(await(s.append(auditBatch)).ackStatus()==AckStatus.DURABLE,"audit transaction retry returns original ACK");
        await(s.checkpoint());close(s);s=open(path);
        check(records(s).contains(region),"indexed 12-bin histogram survives canonical WAL/checkpoint restart");
        check(records(s).contains(audit)&&records(s).contains(original),"1.0.1 audit and request roundtrip checkpoint/restart");
        check(records(s).stream().noneMatch(r->r instanceof DurableFact f&&f.value().equals(fact(231))),"policy rejection leaves no partial fact");
        check(await(s.append(batch(233,List.of(changed),List.of()))).ackStatus()==AckStatus.REJECTED,"policy retry identity survives restart");
        check(await(s.append(batch(234,List.of(policyRequest(envelope,2,RequestSchedulingPolicy.legacyWire())),List.of()))).ackStatus()==AckStatus.DURABLE,"same policy permits subsequent request revision");
        check(requiredSubscription(s,new ConsumerSpec(new ConsumerId(new UUID(234,1)),Set.of(),new Sequence(0),10)).batch().size()==1,"audit retry creates no duplicate outgoing message");
        close(s);System.out.println("PASS API 1.0.1 runtime audit union and semantic/policy idempotence on disk");
    }
    private static void stopPlan101(Path base)throws Exception {
        Path path=base.resolve("stop101");var store=open(path);var authority=new CommitPermit.Authority(store,4);
        var id=new UUID(240,1);var dim=new DimensionId("minecraft:overworld");var chunk=new ChunkRef(dim,0,0);
        var footprint=new Footprint(dim,Set.of(chunk),Set.of(new BlockPosition(dim,1,1,1)),List.of());
        var key=new ResourceKey(OwnershipDomain.BLOCK,dim,"1,1,1");var hash=new Fingerprint("a".repeat(64));var epoch=new Epoch(2);
        var read=new ReadSet(Map.of(),Map.of(key,epoch),Map.of(key,hash));
        var ownership=new OwnershipRecord(new AggregateHeader(1,id,new Revision(2),new Sequence(2),new Tick(2)),key,Set.of(new OwnerToken(id)),epoch,OwnershipDisposition.DIRECTOR_ACTIVE,new MutationId(id),hash,new Epoch(0),Optional.empty());
        var intent=new EffectIntent(new OperationToken(new UUID(240,3)),new PrimitiveId("fixture:stop"),1,new TargetParticles("target",new ResourceId("fixture:stop"),0,0),footprint,new EventInstanceId(id));
        var stop=new PreparedSlice(new SliceId(new UUID(240,2)),new OperationToken(new UUID(240,2)),footprint,read,List.of(intent),BudgetMath.zero());
        var plan=new StabilizationPlan(List.of(new StabilizationSlice(stop,Map.of(key,ownership))));var blob=plan.blob();
        var impact=new PreparedSlice(new SliceId(id),new OperationToken(id),footprint,read,List.of(),BudgetMath.zero());
        var reservation=new ReservationToken(new ReservationId(id),WORLD,new EventInstanceId(id),new Epoch(1),new Tick(200),List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,BudgetMath.zero(),Optional.empty(),epoch)));
        var prepare=new PrepareRecord(new TransactionId(id),impact.operationToken(),new Epoch(1),read,new Tick(200),impact,reservation,new ValidationResult(true,List.of(),read),List.of(),Set.of(),List.of(intent),new EffectBlobs(Set.of(),blob.reference(),blob.reference(),Map.of(),Map.of()),Optional.of(plan));
        var batch=new DurableBatch(prepare.transactionId(),store.session().storeGeneration(),read.revisions(),List.of(new DurablePrepare(prepare)),List.of(blob),Optional.empty(),CommitPermit.Authority.prepareHash(prepare));
        var live=new LiveValidationSnapshot(read,footprint.loadedChunks(),HealthMode.NORMAL,new Tick(100),new Epoch(1));
        authority.submit(batch,live);var granted=poll(authority,new PrepareFixture(batch,impact,live));
        check(granted.status()==PermitResult.Status.GRANTED&&granted.permit().orElseThrow().stabilizationPermit().isPresent(),"forced real stop-plan blob permits authority issuance");
        await(store.checkpoint());close(store);store=open(path);
        check(records(store).contains(new DurablePrepare(prepare)),"stop plan survives WAL/checkpoint canonical replay");
        check(Files.exists(path.resolve("blobs").resolve(blob.reference().hash().value()+".blob")),"stop plan blob retained");
        check(await(store.append(factBatch(240))).ackStatus()==AckStatus.DURABLE,"restart with stop plan remains writable");
        close(store);System.out.println("PASS API 1.0.1 forced stop-plan blob and checkpoint recovery; no world execution");
    }
    private static void migrations(Path base)throws Exception {
        Path p=base.resolve("migration");var s=open(p);await(s.append(factBatch(40)));await(s.checkpoint());
        Path original=p.resolve("checkpoints/2.snapshot");String hash=DurableFiles.hash(original);
        check(await(s.migrate(TEST_MIGRATION))>2&&s.readOnlySafe(),"migration installs whole generation and fences old binary");
        check(DurableFiles.hash(original).equals(hash),"migration source generation intact");close(s);
        s=open(p);check(s.readOnlySafe()&&s.diagnostic().contains("future schema"),"unknown future schema read-only, no reset");close(s);
        // Golden V1->V2 test-only identity transformation preserves every byte, sequence and UUID.
        List<Path> snapshots;try(var files=Files.list(p.resolve("checkpoints"))){snapshots=files.filter(f->f.toString().endsWith(".snapshot")).sorted().toList();}
        check(DurableFiles.hash(snapshots.getLast()).equals(hash),"golden migration frame/hash identity");
        System.out.println("PASS migration generation/source preservation/future schema (test-only migration)");
    }
    private static void recovery(Path base)throws Exception {
        var store=open(base.resolve("recovery-coordinator"));var fixture=prepare(51,store.session());
        var authority=new CommitPermit.Authority(store,2);authority.submit(fixture.batch(),fixture.live());check(poll(authority,fixture).status()==PermitResult.Status.GRANTED,"recovery source durable");
        var dim=new DimensionId("minecraft:overworld");var key=new ResourceKey(OwnershipDomain.values()[0],dim,"test-cell");
        var pre=BlobContent.hash(new byte[]{1});var post=BlobContent.hash(new byte[]{2});var read=new ReadSet(Map.of(),Map.of(key,new Epoch(3)),Map.of(key,pre));
        var snapshot=new SnapshotBatch(new RequestId(new UUID(30,1)),new ScopeSet(WORLD,Set.of()),new Tick(0),Map.of(),new ObservationContext(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of()),Set.of(),Set.of(),HealthMode.NORMAL);
        var intent=new EffectIntent(fixture.slice().operationToken(),new PrimitiveId("fixture:sound"),1,new TargetSound("witness",new ResourceId("minecraft:block.gravel.step"),1,1,1),fixture.slice().footprint(),new EventInstanceId(new UUID(31,1)));
        var prepared=new PreparedEffect(intent,snapshot,List.of(),read,Map.of(key,post),BudgetMath.zero(),List.of(),Set.of(),((DurablePrepare)fixture.batch().records().getFirst()).value().durableBlobs());
        AtomicReference<ReconcileStatus> requested=new AtomicReference<>(ReconcileStatus.SAME_POST);
        AtomicInteger calls=new AtomicInteger();Thread actor=Thread.currentThread();
        var effect=new fr.proutlost.director.api.port.WorldEffectPort(){
            public SliceResult stabilize(PreparedSlice p,CommitPermit.StabilizationPermit permit,StabilizationLiveSnapshot live){throw new AssertionError("recovery stabilized world");}
            public EffectPreparation prepare(EffectIntent i){throw new AssertionError("unexpected world preparation");}
            public SliceResult apply(PreparedSlice p,CommitPermit permit){throw new AssertionError("recovery applied world");}
            public CleanupResult cleanup(CleanupIntent i,CommitPermit permit){throw new AssertionError("recovery cleaned world");}
            public ReconcileResult reconcile(RecoveryProbe p){check(Thread.currentThread()==actor,"reconcile actor thread");calls.incrementAndGet();return new ReconcileResult(requested.get(),p.loadedProofs(),List.of());}
        };
        var coordinator=new RecoveryCoordinator(store,effect,1,3);
        for(var status:ReconcileStatus.values()) {
            requested.set(status);check(coordinator.submit(prepared,new Tick(20),RecoveryMode.RECOVER),"recovery submitted");
            check(!coordinator.submit(prepared,new Tick(20),RecoveryMode.RECOVER),"bounded recovery mailbox");
            var proof=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(status==ReconcileStatus.SAME_PRE_PROVEN?pre:post),Optional.of(new OwnerToken(new UUID(32,1))),new Epoch(3),new Epoch(0),true);
            Optional<RecoveryCoordinator.Decision> decision=Optional.empty();
            for(int n=0;n<200&&decision.isEmpty();n++){decision=coordinator.poll(intent.operationToken(),List.of(proof),new Tick(1));if(decision.isEmpty())Thread.sleep(5);}
            check(decision.isPresent()&&decision.get().status()==status,"proof outcome "+status);
            if(status==ReconcileStatus.SAME_POST)check(decision.get().mode()==RecoveryMode.COMPLETE_FROM_STATE,"post state never respawns");
            if(status==ReconcileStatus.SAME_PRE_PROVEN)check(decision.get().mode()==RecoveryMode.RECOVER,"proven absence needs new admission");
            if(status==ReconcileStatus.UNAVAILABLE) {
                Optional<RecoveryCoordinator.Decision> terminal=Optional.empty();
                for(int n=0;n<200&&terminal.isEmpty();n++){terminal=coordinator.poll(intent.operationToken(),List.of(),new Tick(20));if(terminal.isEmpty())Thread.sleep(5);}
                check(terminal.orElseThrow().terminal(),"unloaded deadline isolates");
            }
        }
        requested.set(ReconcileStatus.SAME_POST);coordinator.submit(prepared,new Tick(30),RecoveryMode.RECOVER);
        Optional<RecoveryCoordinator.Decision> d=Optional.empty();for(int n=0;n<200&&d.isEmpty();n++){d=coordinator.poll(intent.operationToken(),List.of(),new Tick(1));if(d.isEmpty())Thread.sleep(5);}
        check(d.orElseThrow().status()==ReconcileStatus.AMBIGUOUS,"missing provenance never authorizes destructive replay");
        check(calls.get()==6,"all reconcile paths exercised");
        check(coordinator.submit(prepared,new Tick(40),RecoveryMode.CLEANUP),"explicit cleanup policy submitted");
        var postProof=new RecoveryEvidence(key,EvidenceStatus.CONFIRMED,Optional.of(post),Optional.of(new OwnerToken(new UUID(32,1))),new Epoch(3),new Epoch(0),false);
        Optional<RecoveryCoordinator.Decision> cleanup=Optional.empty();
        for(int n=0;n<200&&cleanup.isEmpty();n++){cleanup=coordinator.poll(intent.operationToken(),List.of(postProof),new Tick(1));if(cleanup.isEmpty())Thread.sleep(5);}
        check(cleanup.orElseThrow().mode()==RecoveryMode.CLEANUP,"recipe cleanup preserved for proven state");
        check(records(store).stream().filter(r->r instanceof DurableFact f && f.value().factType().value().equals("proutlost_director:recovery_decision")).count()==7,"recovery dossiers persisted before decisions");
        close(store);
        System.out.println("PASS actor recovery decisions; no world effects");
    }
    private static void revisionsAndUniqueness(Path base)throws Exception {
        Path path=base.resolve("revisions");var store=open(path);
        UUID uniqueId=new UUID(50,1);DefinitionId definition=new DefinitionId("fixture:once");
        var unique=new UniqueUseRecord(new AggregateHeader(1,uniqueId,new Revision(1),new Sequence(0),new Tick(1)),definition,UniquenessScope.ONCE_PER_WORLD,WORLD.value(),UniqueStatus.SPENT_POSSIBLE,Optional.empty(),new EventInstanceId(new UUID(51,1)),Optional.empty(),Set.of());
        check(await(store.append(batch(60,List.of(new DurableUnique(unique)),List.of()))).ackStatus()==AckStatus.DURABLE,"unique exposure ledger");
        var reset=new UniqueUseRecord(new AggregateHeader(1,uniqueId,new Revision(2),new Sequence(0),new Tick(2)),definition,UniquenessScope.ONCE_PER_WORLD,WORLD.value(),UniqueStatus.RESERVED,Optional.empty(),unique.instanceId(),Optional.empty(),Set.of());
        check(await(store.append(batch(61,List.of(new DurableUnique(reset)),List.of()))).ackStatus()==AckStatus.REJECTED,"uncertain unique never reset");
        var duplicate=new UniqueUseRecord(new AggregateHeader(1,new UUID(50,2),new Revision(1),new Sequence(0),new Tick(2)),definition,UniquenessScope.ONCE_PER_WORLD,WORLD.value(),UniqueStatus.RESERVED,Optional.empty(),unique.instanceId(),Optional.empty(),Set.of());
        check(await(store.append(batch(62,List.of(new DurableUnique(duplicate)),List.of()))).ackStatus()==AckStatus.REJECTED,"unique composite key prevents new UUID bypass");
        var key=new AggregateKey(AggregateKind.UNIQUE,uniqueId);var view=requiredView(store,new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1));
        check(view.aggregates().getFirst().state().equals(new UniqueAggregate(unique)),"restored typed aggregate projection");
        var scope=new ScopeRef(ScopeKind.REGION,new UUID(52,1));
        var delta=new DurableMemoryDelta(new AggregateDelta(scope,new Revision(0),new Revision(1),Map.of()));
        var deltaBatch=new DurableBatch(new TransactionId(new UUID(53,1)),new Epoch(1),Map.of(scope,new Revision(0)),List.of(delta),List.of(),Optional.empty(),BlobContent.hash(new byte[]{1}));
        check(await(store.append(deltaBatch)).ackStatus()==AckStatus.DURABLE,"scope revision CAS update");
        var stale=new DurableBatch(new TransactionId(new UUID(53,2)),new Epoch(1),Map.of(scope,new Revision(0)),List.of(new DurableFact(fact(64))),List.of(),Optional.empty(),BlobContent.hash(new byte[]{2}));
        check(await(store.append(stale)).ackStatus()==AckStatus.REJECTED,"stale revision rejects entire batch");
        var page=requiredView(store,new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),1));
        check(page.records().size()==1&&page.hasMore(),"bounded projection page");
        var page2=requiredView(store,new StoreQuery(WORLD,Set.of(),new Sequence(0),page.nextCursor(),1));check(page2.records().size()==1&&!page2.hasMore(),"continuation cursor");
        await(store.append(factBatch(65)));
        check(store.view(new StoreQuery(WORLD,Set.of(),new Sequence(0),page.nextCursor(),1)).status()==ReadStatus.STALE,"stale page rejected with typed status");
        close(store);store=open(path);check(requiredView(store,new StoreQuery(WORLD,Set.of(key),new Sequence(0),Optional.empty(),1)).aggregates().getFirst().state().equals(new UniqueAggregate(unique)),"unique projection reboot");close(store);
        System.out.println("PASS revisions, immutable pagination and uniqueness");
    }
    private static void orderedFrames(Path base)throws Exception {
        Path source=base.resolve("ordered-source");var s=open(source);await(s.append(factBatch(1)));close(s);
        for(String fault:List.of("HASH","UUID","SEQUENCE")) {
            Path p=base.resolve("ordered-"+fault);copy(source,p);Path segment=wal(p).getFirst();
            var bytes=new ByteArrayOutputStream();boolean changed=false;
            try(var c=FileChannel.open(segment)) {
                for(WalFrame.Frame frame;(frame=WalFrame.read(c,OPTIONS.frameBytes()))!=null;) {
                    byte[] encoded=frame.encoded();
                    if(!changed&&frame.type()==WalFrame.Type.RECORD) {
                        encoded=WalFrame.encode(frame.type(),frame.sequence()+(fault.equals("SEQUENCE")?1:0),fault.equals("UUID")?UUID.randomUUID():frame.transaction(),fault.equals("HASH")?new ModelCodec<>(DurableRecord.class).encode(new DurableFact(fact(99))):frame.payload(),OPTIONS.frameBytes());changed=true;
                    } bytes.write(encoded);
                }
            } Files.write(segment,bytes.toByteArray());s=open(p);check(s.readOnlySafe()&&s.view(new StoreQuery(WORLD,Set.of(),new Sequence(0),Optional.empty(),1024)).status()==ReadStatus.UNAVAILABLE,"valid CRC but bad "+fault+" isolated");close(s);
        }
        System.out.println("PASS ordered hash, UUID and sequence corruption with valid CRC");
    }
    private static void asyncWriter(Path base)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var armed=new AtomicBoolean();
        var s=await(JournalStore.open(base.resolve("async"),WORLD,OPTIONS,p->{if(armed.get()&&p.equals("BEFORE_WAL_FORCE")){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test gate timeout");}catch(InterruptedException e){throw new IOException(e);}}}));
        armed.set(true);var ack=s.append(factBatch(70));check(entered.await(10,TimeUnit.SECONDS),"writer reached async force gate");
        check(!ack.toCompletableFuture().isDone()&&records(s).isEmpty(),"no ACK or published state before force");
        release.countDown();check(await(ack).ackStatus()==AckStatus.DURABLE&&records(s).size()==2,"publication follows durable force");close(s);
        System.out.println("PASS asynchronous writer and force-before-ACK");
    }
    private static void centralProfile(Path base)throws Exception {
        Path source=Path.of("build/director-input/spec-1.0.0/DEFAULT_FOR_TESTING.json");
        var profile=fr.proutlost.director.config.TestingConfig.load(Files.readAllBytes(source));
        var options=StoreOptions.fromConfig(profile.view(),32,128);
        check(options.frameBytes()==profile.get(fr.proutlost.director.config.ConfigRegistry.IO_FRAME_MAX_BYTES),"real central frame capacity");
        check(options.frameBytes()==4_194_304 && options.writeBatchBytes()==262_144 && options.checkpointGenerations()==3,"central capacities not alternate constants");
        var store=await(JournalStore.open(base.resolve("central-profile"),WORLD,options));
        check(!store.readOnlySafe(),"store boots using actual central config");
        check(await(store.append(factBatch(101))).ackStatus()==AckStatus.DURABLE,"central-config real disk transaction");
        var f=prepare(101,store.session());var authority=new CommitPermit.Authority(store,2);authority.submit(f.batch(),f.live());
        check(poll(authority,f).status()==PermitResult.Status.GRANTED,"central-config blobs and PREPARE");
        close(store);System.out.println("PASS authenticated DEFAULT_FOR_TESTING via frozen config API");
    }
    private static void sharedIndexes() {
        var actual=new SharedMap<Integer,String>(Integer::compare);var expected=new TreeMap<Integer,String>();
        var snapshots=new ArrayList<SharedMap<Integer,String>>();var golden=new ArrayList<Map<Integer,String>>();
        var random=new Random(206);
        for(int i=0;i<4096;i++) {
            if(i%257==0){var copy=new SharedMap<>(actual);check(copy.sharesRoot(actual),"index snapshot shares root without copying history");snapshots.add(copy);golden.add(Map.copyOf(expected));}
            int key=random.nextInt(8192);String value="v"+i;
            check(Objects.equals(actual.put(key,value),expected.put(key,value)),"persistent index replacement oracle");
            check(actual.lastUpdateNodes()<100,"append copies logarithmic path, not all historical entries");
        }
        check(actual.equals(expected),"persistent AVL exact values against JDK TreeMap");
        for(int i=0;i<snapshots.size();i++)check(snapshots.get(i).equals(golden.get(i)),"old published snapshots remain immutable");
        var tail=new ArrayList<String>();actual.tailValues(4000).forEach(tail::add);check(tail.equals(new ArrayList<>(expected.tailMap(4000).values())),"bounded ordered seek");
        System.out.println("PASS shared immutable indexes and bounded update paths");
    }
    private static void retention(Path base)throws Exception {
        Path p=base.resolve("retention");var store=open(p);var fixture=prepare(111,store.session());
        var authority=new CommitPermit.Authority(store,2);authority.submit(fixture.batch(),fixture.live());check(poll(authority,fixture).status()==PermitResult.Status.GRANTED,"retention live PREPARE");
        var orphan=BlobContent.capture(new ResourceId("fixture:unreferenced"),1,new byte[]{9,8,7});
        check(await(store.append(batch(112,List.of(),List.of(orphan)))).ackStatus()==AckStatus.DURABLE,"unreferenced blob fixture written");
        var consumer=new ConsumerId(new UUID(113,1));var inbox=new DurableInbox(new InboxRecord(consumer,new MessageId(new UUID(114,1)),BlobContent.hash(new byte[]{1}),new Sequence(0),new Revision(1)));
        check(await(store.append(batch(113,List.of(inbox),List.of()))).ackStatus()==AckStatus.DURABLE,"retention inbox");
        var resource=new ResourceKey(OwnershipDomain.values()[0],new DimensionId("minecraft:overworld"),"retained-cell");
        var owner=new OwnershipRecord(new AggregateHeader(1,new UUID(115,1),new Revision(1),new Sequence(0),new Tick(1)),resource,Set.of(new OwnerToken(new UUID(116,1))),new Epoch(2),OwnershipDisposition.YIELDED_TO_PLAYER,new MutationId(new UUID(117,1)),BlobContent.hash(new byte[]{1}),new Epoch(1),Optional.empty());
        check(await(store.append(batch(114,List.of(new DurableOwnership(owner)),List.of()))).ackStatus()==AckStatus.DURABLE,"retention ownership tombstone");
        long originalPartBytes=0;
        for(int i=0;i<6;i++) {
            await(store.append(factBatch(120+i)));check(await(store.checkpoint())>1,"retention checkpoint durable");
            try(var files=Files.list(p.resolve("checkpoints/objects"))) {
                long bytes=0;for(var part:files.filter(f->f.toString().endsWith(".part")).toList())bytes+=Files.size(part);
                if(i==0)originalPartBytes=bytes;
                check(bytes<=originalPartBytes+20_000L*i,"checkpoint parts grow only with new WAL, not repeated history");
            }
        }
        long snapshotCount;try(var files=Files.list(p.resolve("checkpoints"))){snapshotCount=files.filter(f->f.toString().endsWith(".snapshot")).count();}
        check(snapshotCount==3,"configured three generations retained");check(wal(p).size()<=2,"old WAL physically reclaimed after all retained generations cover it");
        check(!Files.exists(p.resolve("blobs").resolve(orphan.reference().hash().value()+".blob")),"unreferenced orphan collected");
        check(Files.exists(p.resolve("blobs").resolve(fixture.batch().blobs().getFirst().reference().hash().value()+".blob")),"live recovery blob retained");
        close(store);store=open(p);check(!store.readOnlySafe(),"retained checkpoint restarts after WAL deletion");
        check(requiredSubscription(store,new ConsumerSpec(consumer,Set.of(),new Sequence(0),128)).batch().size()==6,"all unacked outbox retained");
        check(await(store.append(batch(150,List.of(inbox,new DurableFact(fact(150))),List.of()))).ackStatus()==AckStatus.REJECTED,"inbox dedup retained");
        var view=requiredView(store,new StoreQuery(WORLD,Set.of(new AggregateKey(AggregateKind.OWNERSHIP,owner.header().id())),new Sequence(0),Optional.empty(),1));
        check(((OwnershipAggregate)view.aggregates().getFirst().state()).value().equals(owner),"ownership tombstone retained exactly");
        check(records(store).stream().filter(r->r instanceof DurableFact).count()==6,"durable facts preserved");
        close(store);System.out.println("PASS shared checkpoint parts, generations, old WAL cleanup and exact retained guarantees");
    }
    private static void child(Path path,String point)throws Exception {
        AtomicBoolean armed=new AtomicBoolean();String hook=point.equals("CONSUMER_ACK_BEGIN")?"TX_BEGIN":point.equals("CONSUMER_ACK_FORCED")?"WAL_FORCED":point;
        var s=await(JournalStore.open(path,WORLD,OPTIONS,p->{if(armed.get()&&p.equals(hook)){
            System.out.println("KILL_POINT "+point);System.out.flush();try{Thread.sleep(60000);}catch(InterruptedException e){throw new IOException(e);}
        }}));
        check(!s.readOnlySafe(),s.diagnostic());armed.set(true);
        if(point.contains("OLD_WAL_CLEANUP")) {
            armed.set(false);await(s.append(factBatch(77)));await(s.checkpoint());await(s.checkpoint());
            armed.set(true);await(s.checkpoint());
        }
        else if(point.startsWith("CONSUMER")||point.equals("OUTBOX_DELIVERED")) {
            armed.set(false);await(s.append(factBatch(77)));
            var consumer=new ConsumerId(new UUID(90,1));var subscription=requiredSubscription(s,new ConsumerSpec(consumer,Set.of(),new Sequence(0),10));
            if(point.equals("OUTBOX_DELIVERED")){System.out.println("KILL_POINT "+point);System.out.flush();Thread.sleep(60000);}
            armed.set(true);await(s.acknowledge(new ConsumerAck(consumer,Set.of(subscription.batch().getFirst().messageId()),new Sequence(1),new Sequence(0))));
        }
        else if(point.startsWith("MIGRATION")){await(s.append(factBatch(77)));await(s.migrate(TEST_MIGRATION));}
        else if(point.contains("CHECKPOINT")||point.contains("MANIFEST")){await(s.append(factBatch(77)));await(s.checkpoint());}
        else if(point.contains("BLOB")){var f=prepare(77,s.session());var a=new CommitPermit.Authority(s,2);a.submit(f.batch(),f.live());poll(a,f);}
        else await(s.append(factBatch(77)));
        throw new AssertionError("crash point not reached");
    }
    private static void crashProcesses(Path base)throws Exception {
        for(String point:List.of("BLOB_WRITTEN","BLOB_FILE_FORCED","BLOB_RENAMED","BLOB_FORCED","BLOBS_FORCED","TX_BEGIN","TX_RECORD","TX_COMMIT","WAL_FORCED","BEFORE_ACK","CHECKPOINT_FORCED","BEFORE_MANIFEST","MANIFEST_DURABLE","MIGRATION_RECORD","MIGRATION_FORCED","MIGRATION_BEFORE_MANIFEST","OUTBOX_DELIVERED","CONSUMER_ACK_BEGIN","CONSUMER_ACK_FORCED","BEFORE_OLD_WAL_CLEANUP","OLD_WAL_CLEANUP")) {
            Path p=base.resolve("kill-"+point);Path log=base.resolve("kill-"+point+".log");
            var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms32m","-Xmx256m","-XX:ActiveProcessorCount=1","-cp",System.getProperty("java.class.path"),StoreTests.class.getName(),"child",p.toString(),point);
            Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean reached=false;
                while(System.nanoTime()<end&&child.isAlive()){if(Files.exists(log)&&Files.readString(log).contains("KILL_POINT "+point)){reached=true;break;}Thread.sleep(20);}
                check(reached,"child reached "+point+": "+Files.readString(log));child.destroyForcibly();check(child.waitFor(10,TimeUnit.SECONDS),"child killed");check(child.exitValue()!=0,"actual abnormal process termination");
            }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
            var s=open(p);check(!s.readOnlySafe(),point+" restart healthy: "+s.diagnostic());
            int count=records(s).size();
            boolean committed=Set.of("TX_COMMIT","WAL_FORCED","BEFORE_ACK","CHECKPOINT_FORCED","BEFORE_MANIFEST","MANIFEST_DURABLE","MIGRATION_RECORD","MIGRATION_FORCED","MIGRATION_BEFORE_MANIFEST","OUTBOX_DELIVERED","CONSUMER_ACK_BEGIN","CONSUMER_ACK_FORCED","BEFORE_OLD_WAL_CLEANUP","OLD_WAL_CLEANUP").contains(point);
            check(count==(committed?2:0),"atomic recovery at "+point+" count="+count);
            if(point.equals("BLOB_RENAMED")) {
                close(s);List<String> trace=Collections.synchronizedList(new ArrayList<>());
                s=await(JournalStore.open(p,WORLD,OPTIONS,trace::add));trace.clear();
                var original=prepare(78,s.session());
                var b=original.batch();var withoutBytes=new DurableBatch(b.transactionId(),b.expectedStoreGeneration(),b.expectedAggregateRevisions(),b.records(),List.of(),b.permitAttempt(),b.transactionHash());
                var retry=new PrepareFixture(withoutBytes,original.slice(),original.live());var authority=new CommitPermit.Authority(s,2);
                authority.submit(retry.batch(),retry.live());check(poll(authority,retry).status()==PermitResult.Status.GRANTED,"orphan after SIGKILL reused without supplying bytes");
                int file=trace.indexOf("BLOB_REUSED_FILE_FORCED"),directory=trace.indexOf("BLOB_REUSED_DIRECTORY_FORCED"),begin=trace.indexOf("TX_BEGIN"),forced=trace.indexOf("WAL_FORCED"),ack=trace.indexOf("BEFORE_ACK");
                check(file>=0&&directory>file&&begin>directory&&forced>begin&&ack>forced,"orphan retry file/directory force precedes WAL and ACK: "+trace);
                count=records(s).size();
            }
            if(point.startsWith("CONSUMER")||point.equals("OUTBOX_DELIVERED")) {
                var subscription=requiredSubscription(s,new ConsumerSpec(new ConsumerId(new UUID(90,1)),Set.of(),new Sequence(0),10));
                check(subscription.batch().size()==(point.equals("CONSUMER_ACK_FORCED")?0:1),"durable consumer cursor/replay "+point);
                if(!subscription.batch().isEmpty())check(subscription.batch().getFirst().messageId().equals(message(77).messageId())&&subscription.batch().getFirst().worldSequence().orElseThrow().value()==1,"redelivery retains identity/sequence");
            }
            check(await(s.append(factBatch(99))).ackStatus()==AckStatus.DURABLE,"post-crash writable "+point);close(s);
            s=open(p);check(records(s).size()==count+2&&!s.readOnlySafe(),"second restart "+point);close(s);
            System.out.println("PASS SIGKILL/relaunch "+point);
        }
    }
    private static void recordedArchive(Path base)throws Exception {
        var profile=fr.proutlost.director.config.TestingConfig.load(Files.readAllBytes(Path.of("build/director-input/spec-1.0.0/DEFAULT_FOR_TESTING.json")));
        var input=fr.proutlost.director.engine.DecisionPipelineTest.archiveInput(profile.view());
        var world=input.state().world().worldId();var directory=base.resolve("recorded-archive");
        var store=await(JournalStore.open(directory,world,OPTIONS));
        var initial=fr.proutlost.director.runtime.ResultTransaction.batch(new TransactionId(UUID.randomUUID()),store.session().storeGeneration(),
                Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),new Revision(0)),List.of(new DurableAggregate(new WorldAggregate(input.state().world()))),List.of());
        check(await(store.append(initial)).ackStatus()==AckStatus.DURABLE,"archive baseline WORLD is real durable state");
        var engine=new fr.proutlost.director.engine.WorldDirector(ProviderMode.TEST_ONLY,new fr.proutlost.director.engine.CandidatePlanner(
                request->{throw new AssertionError("no test planning capture");},Map.of()));
        var proposal=engine.evaluate(input);
        var archive=fr.proutlost.director.bootstrap.RecordedDecisionArchive.record(input,proposal,Map.of(),Map.of(),store.session(),ProviderMode.TEST_ONLY);
        var journal=new fr.proutlost.director.bootstrap.DirectorDecisionJournal(store,2);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var live=new fr.proutlost.director.bootstrap.DirectorReadOnlyInputs(store,ProviderMode.TEST_ONLY,worker,base.resolve("live-audit-no-fixtures"));
            await(CompletableFuture.runAsync(()->{},worker));
            check(await(CompletableFuture.supplyAsync(()->{try{live.audit(proposal.proposalId());return false;}catch(fr.proutlost.director.admin.server.AdminProjectionPages.Rejected absent){return absent.reason()==fr.proutlost.director.api.error.ReasonCode.NO_TARGET;}},worker)),"audit resolver created before first decision reports actual bounded absence");
            try{live.audit(proposal.proposalId());throw new AssertionError("audit IO accepted on actor");}catch(IllegalStateException expected){check(true,"audit lookup cannot perform IO on actor");}
            check(journal.submitRecorded(proposal,archive.getKey(),archive.getValue()).state()==fr.proutlost.director.bootstrap.DirectorDecisionJournal.State.WAITING,"recorded decision awaits real WAL ACK");
            check(await(store.checkpoint())>0&&journal.poll(proposal.proposalId()).mayProceedToAdmission(),"archive/audit/WORLD committed atomically before proof");
            var actual=await(CompletableFuture.supplyAsync(()->live.audit(proposal.proposalId()),worker));
            check(actual.equals(proposal.audit()),"same already-created resolver sees first newly committed audit without restart or cache injection");
            var auditCountQuery=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),1024);
            int before=requiredView(store,auditCountQuery).records().size();
            var exporter=new fr.proutlost.director.admin.server.AdminAuditExporter(base.resolve("live-audit-exports"),worker,1024*1024);
            var exported=await(exporter.export(actual,()->true));
            check(new ModelCodec<>(AuditDecision.class).decode(Files.readAllBytes(exported)).equals(proposal.audit()),"fresh real-store audit exported with existing codec/exporter");
            check(requiredView(store,auditCountQuery).records().size()==before,"fresh audit lookup/export writes no J04 records or decision context");
        }
        var refs=DurableBlobReferences.recordedDecision(archive.getKey());
        check(await(store.readBlobs(new BlobReadRequest(world,store.session().storeGeneration(),refs))).contents().equals(archive.getValue()),"checkpoint retention keeps typed archive reference");
        close(store);
        var reopened=await(JournalStore.open(directory,world,OPTIONS));
        var hydrated=await(reopened.readBlobs(new BlobReadRequest(world,reopened.session().storeGeneration(),refs))).contents();
        check(fr.proutlost.director.bootstrap.RecordedDecisionArchive.replay(archive.getKey(),proposal.audit(),hydrated,reopened.session(),ProviderMode.TEST_ONLY,false).equals(proposal),"real checkpoint/close/reopen preserves exact recorded replay");
        Path fixtures=base.resolve("operator-archive-fixtures");Files.createDirectories(fixtures.resolve("qa"));
        byte[] bytes=new ModelCodec<>(SnapshotBatch.class).encode(input.context());
        Path file=fixtures.resolve("qa/captured.json"),hash=file.resolveSibling("captured.json.sha256");
        Files.write(file,bytes);Files.writeString(hash,BlobContent.hash(bytes).value());
        try(var worker=Executors.newSingleThreadExecutor()) {
            var inputs=new fr.proutlost.director.bootstrap.DirectorReadOnlyInputs(reopened,ProviderMode.TEST_ONLY,worker,fixtures);
            await(CompletableFuture.runAsync(()->{},worker));
            check(inputs.fixture(new ResourceId("qa:captured")).equals(input.context()),"operator SnapshotBatch loaded by existing codec and SHA");
            var countQuery=new StoreQuery(world,Set.of(),new Sequence(0),Optional.empty(),1024);
            int count=requiredView(reopened,countQuery).records().size();
            check(await(CompletableFuture.supplyAsync(()->inputs.audit(proposal.proposalId()),worker)).equals(proposal.audit()),"same audit export resolver supports actual cold J04 history");
            check(await(CompletableFuture.supplyAsync(()->inputs.replay(proposal.proposalId(),false),worker)).equals(proposal),"admin resolver reads exact input from real J04 after restart");
            var simulation=await(CompletableFuture.supplyAsync(()->inputs.calculations().simulate(new ResourceId("qa:captured"),inputs.fixture(new ResourceId("qa:captured"))),worker));
            check(simulation.audit().context().equals(input.context())&&requiredView(reopened,countQuery).records().size()==count,"registered simulation is real pure evaluation with no WAL/world mutation");
            Files.writeString(hash,"0".repeat(64));
            try{fr.proutlost.director.bootstrap.DirectorReadOnlyInputs.loadFixtures(fixtures,world);throw new AssertionError("tampered operator SHA accepted");}
            catch(IOException expected){check(true,"operator hash mismatch rejected");}
            Files.writeString(hash,BlobContent.hash(bytes).value());
            var foreign=new SnapshotBatch(input.context().requestId(),new ScopeSet(new WorldId(UUID.randomUUID()),input.context().scopes().scopes()),input.context().capturedTick(),
                    input.context().scopeRevisions(),input.context().context(),input.context().missingData(),input.context().loadedChunks(),input.context().serverHealth());
            byte[] invalid=new ModelCodec<>(SnapshotBatch.class).encode(foreign);Files.write(file,invalid);Files.writeString(hash,BlobContent.hash(invalid).value());
            try{fr.proutlost.director.bootstrap.DirectorReadOnlyInputs.loadFixtures(fixtures,world);throw new AssertionError("foreign operator world accepted");}
            catch(IOException expected){check(true,"operator foreign world/scopes rejected");}
        }
        close(reopened);
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("child")){child(Path.of(args[1]),args[2]);return;}
        Path base=Path.of(args[0]);Files.createDirectories(base);
        centralProfile(base);recordedArchive(base);sharedIndexes();frames(base);roundTrip(base);permits(base);corruption(base);failures(base);inboxAndCheckpoint(base);independentCursors(base);amendment101(base);stopPlan101(base);migrations(base);recovery(base);revisionsAndUniqueness(base);orderedFrames(base);asyncWriter(base);retention(base);crashProcesses(base);
        ChainJournalStoreTests.run(base.resolve("chain-region-cas"));
        System.out.println("PASS assertions="+assertions);
    }
}
