package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Writer-owned transaction candidate. Published copies are never mutated. */
final class StoreProjection {
    record Entry(long sequence, DurableRecord record) {}
    record Receipt(String requestDigest, CommitAck ack) {}
    record Claim(WorldId world, ReservationId reservation, Epoch generation, OperationToken operation) {}
    record InboxKey(ConsumerId consumer, MessageId message) {}
    record ProducerKey(ResourceId producer, String key) {}
    record RequestIdentity(Fingerprint semantic, Fingerprint policy) {}
    record UniqueKey(DefinitionId definition, UniquenessScope scope, UUID id) {}
    final SharedList<Entry> entries;
    // Only the chronology can be cold. Operational maps below NEVER evict.
    final long archivedThroughSequence;
    final long archivedReadRows;
    final SharedMap<AggregateKey, RestoredAggregate> aggregates;
    final SharedMap<ResourceId,MetadataGenerations.Stage> metadataStaging;
    final SharedMap<ResourceId,MetadataGenerations.Stage> metadataSources;
    final SharedMap<ResourceId,LocationImport> metadataLegacySources;
    final SharedMap<ResourceId,LocationTransferManifest> metadataLegacyManifests;
    final SharedSet<Fingerprint> metadataCancelled;
    final SharedMap<Fingerprint,Tick> metadataPreparationClocks;
    final SharedMap<AggregateKey,MetadataGenerations.Head> metadataHeads;
    // Writer-derived per-kind change token: cold hydration and unrelated WAL
    // records do not change the visible head census. Replay reconstructs it.
    final SharedMap<AggregateKind,Long> metadataHeadVersions;
    final SharedMap<PlayerId,Long> metadataSignificantVersions;
    final SharedMap<AggregateKey,Fingerprint> metadataSelectionFingerprints;
    final SharedMap<AggregateKind,Long> metadataSelectionVersions;
    final SharedMap<AggregateKey,ResourceId> metadataOwners;
    final SharedMap<MetadataGenerations.LocationIdentity,LocationId> metadataLocationIds;
    final SharedMap<LocationId,MetadataGenerations.LocationIdentity> metadataExternalIds;
    final SharedMap<ScopeRef, Revision> revisions;
    final SharedMap<TransactionId, Receipt> transactions;
    final SharedSet<Claim> claims;
    final SharedMap<MessageId, DirectorEnvelope> messages;
    final SharedMap<Long,DirectorEnvelope> outbox;
    final SharedMap<FactId, DirectorFact> facts;
    final SharedMap<InboxKey, InboxRecord> inbox;
    // Incoming producer positions and outgoing delivery positions are independent domains.
    final SharedMap<ConsumerId, Long> inboxCursors;
    final SharedMap<ConsumerId, Long> cursors;
    final SharedMap<ProducerKey, RequestIdentity> requestKeys;
    final SharedMap<UniqueKey,UniqueUseRecord> unique;
    final SharedMap<ResourceKey,OwnershipRecord> owners;
    final SharedMap<TransactionId,PrepareRecord> prepares;
    final SharedSet<OperationToken> results;
    final SharedSet<BlobRef> references;
    long sequence, messageSequence,metadataClockFloor;
    StoreProjection() {
        archivedThroughSequence=0;
        archivedReadRows=0;
        metadataStaging=new SharedMap<>();metadataSources=new SharedMap<>();metadataLegacySources=new SharedMap<>();metadataLegacyManifests=new SharedMap<>();metadataCancelled=new SharedSet<>();metadataPreparationClocks=new SharedMap<>();metadataHeads=new SharedMap<>();metadataHeadVersions=new SharedMap<>();metadataSignificantVersions=new SharedMap<>();metadataSelectionFingerprints=new SharedMap<>();metadataSelectionVersions=new SharedMap<>();metadataOwners=new SharedMap<>();metadataLocationIds=new SharedMap<>();metadataExternalIds=new SharedMap<>();
        entries=new SharedList<>();aggregates=new SharedMap<>();revisions=new SharedMap<>();transactions=new SharedMap<>();
        claims=new SharedSet<>();messages=new SharedMap<>();outbox=new SharedMap<>(Long::compare);facts=new SharedMap<>();
        inbox=new SharedMap<>();inboxCursors=new SharedMap<>();cursors=new SharedMap<>();requestKeys=new SharedMap<>();unique=new SharedMap<>();
        owners=new SharedMap<>();prepares=new SharedMap<>();results=new SharedSet<>();references=new SharedSet<>();
    }
    StoreProjection(StoreProjection old) {
        archivedThroughSequence=old.archivedThroughSequence;
        archivedReadRows=old.archivedReadRows;
        entries=new SharedList<>(old.entries);metadataStaging=new SharedMap<>(old.metadataStaging);metadataSources=new SharedMap<>(old.metadataSources);metadataLegacySources=new SharedMap<>(old.metadataLegacySources);metadataLegacyManifests=new SharedMap<>(old.metadataLegacyManifests);metadataCancelled=new SharedSet<>(old.metadataCancelled);metadataPreparationClocks=new SharedMap<>(old.metadataPreparationClocks);metadataClockFloor=old.metadataClockFloor;
        metadataHeads=new SharedMap<>(old.metadataHeads);metadataHeadVersions=new SharedMap<>(old.metadataHeadVersions);metadataSignificantVersions=new SharedMap<>(old.metadataSignificantVersions);metadataSelectionFingerprints=new SharedMap<>(old.metadataSelectionFingerprints);metadataSelectionVersions=new SharedMap<>(old.metadataSelectionVersions);metadataOwners=new SharedMap<>(old.metadataOwners);metadataLocationIds=new SharedMap<>(old.metadataLocationIds);metadataExternalIds=new SharedMap<>(old.metadataExternalIds);
        aggregates=new SharedMap<>(old.aggregates);revisions=new SharedMap<>(old.revisions);
        transactions=new SharedMap<>(old.transactions);claims=new SharedSet<>(old.claims);messages=new SharedMap<>(old.messages);
        outbox=new SharedMap<>(old.outbox);facts=new SharedMap<>(old.facts);inbox=new SharedMap<>(old.inbox);
        inboxCursors=new SharedMap<>(old.inboxCursors);cursors=new SharedMap<>(old.cursors);requestKeys=new SharedMap<>(old.requestKeys);unique=new SharedMap<>(old.unique);
        owners=new SharedMap<>(old.owners);prepares=new SharedMap<>(old.prepares);results=new SharedSet<>(old.results);
        references=new SharedSet<>(old.references);sequence=old.sequence;messageSequence=old.messageSequence;
    }
    /** Clone operational state without retaining already checkpointed chronology.
     * The verified sidecar row count is an external invariant; never guess it.
     */
    StoreProjection(StoreProjection old,long verifiedCut,long verifiedReadRowTotal) {
        archivedThroughSequence=verifiedCut;
        archivedReadRows=verifiedReadRowTotal;
        entries=new SharedList<>();
        if(verifiedCut<old.archivedThroughSequence || verifiedCut>old.sequence)
            throw new IllegalArgumentException("cold sequence cut regression");
        long removed=0;
        for(var row:old.entries) {
            if(row.sequence()<=verifiedCut)removed++;
            else entries.add(row);
        }
        if(verifiedReadRowTotal!=Math.addExact(old.archivedReadRows,removed))
            throw new IllegalArgumentException("archive and resident read-row count disagree");
        metadataStaging=new SharedMap<>(old.metadataStaging);metadataSources=new SharedMap<>(old.metadataSources);metadataLegacySources=new SharedMap<>(old.metadataLegacySources);metadataLegacyManifests=new SharedMap<>(old.metadataLegacyManifests);metadataCancelled=new SharedSet<>(old.metadataCancelled);metadataPreparationClocks=new SharedMap<>(old.metadataPreparationClocks);metadataClockFloor=old.metadataClockFloor;
        metadataHeads=new SharedMap<>(old.metadataHeads);metadataHeadVersions=new SharedMap<>(old.metadataHeadVersions);metadataSignificantVersions=new SharedMap<>(old.metadataSignificantVersions);metadataSelectionFingerprints=new SharedMap<>(old.metadataSelectionFingerprints);metadataSelectionVersions=new SharedMap<>(old.metadataSelectionVersions);metadataOwners=new SharedMap<>(old.metadataOwners);metadataLocationIds=new SharedMap<>(old.metadataLocationIds);metadataExternalIds=new SharedMap<>(old.metadataExternalIds);
        aggregates=new SharedMap<>(old.aggregates);revisions=new SharedMap<>(old.revisions);
        transactions=new SharedMap<>(old.transactions);claims=new SharedSet<>(old.claims);
        messages=new SharedMap<>(old.messages);outbox=new SharedMap<>(old.outbox);
        facts=new SharedMap<>(old.facts);inbox=new SharedMap<>(old.inbox);
        inboxCursors=new SharedMap<>(old.inboxCursors);cursors=new SharedMap<>(old.cursors);
        requestKeys=new SharedMap<>(old.requestKeys);unique=new SharedMap<>(old.unique);
        owners=new SharedMap<>(old.owners);prepares=new SharedMap<>(old.prepares);
        results=new SharedSet<>(old.results);references=new SharedSet<>(old.references);
        sequence=old.sequence;messageSequence=old.messageSequence;
    }
    static Claim claim(PermitAttempt a) { return new Claim(a.session().worldId(), a.reservationId(), a.reservationGeneration(), a.operationToken()); }
    static AggregateState aggregate(DurableRecord r) {
        return switch (r) {
            case DurableAggregate a -> a.state();
            case DurableMutation a -> new MutationAggregate(a.value());
            case DurableOwnership a -> new OwnershipAggregate(a.value());
            case DurableUnique a -> new UniqueAggregate(a.value());
            case DurableRequest a -> new RequestAggregate(a.request().state());
            default -> null;
        };
    }
    void add(DurableRecord record, long seq, Fingerprint hash) {
        AggregateState state = aggregate(record);
        if (state != null) {
            var old = aggregates.get(state.key());
            long expected = Math.incrementExact(MetadataGenerations.revision(this,state.key()).value());
            require(state.header().revision().value() == expected, "aggregate revision mismatch");
            require(state.header().lastSequence().value() <= seq, "aggregate future sequence");
            if (state instanceof WorldAggregate nextWorld && old != null && old.state() instanceof WorldAggregate oldWorld)
                require(nextWorld.value().simTick().compareTo(oldWorld.value().simTick())>=0,"durable clock regression");
            aggregates.put(state.key(), new RestoredAggregate(state, new Sequence(seq), hash));
            metadataHeadVersions.put(state.key().kind(),seq);
            MetadataSelectionCensus.update(this,state,seq);
            if(state instanceof PlayerSignificantAggregate significant){
                metadataSignificantVersions.put(significant.value().playerId(),seq);
                if(old!=null&&old.state() instanceof PlayerSignificantAggregate previous)
                    metadataSignificantVersions.put(previous.value().playerId(),seq);
            }
            ScopeKind scope = switch (state.key().kind()) {
                case WORLD -> ScopeKind.WORLD;
                case REGION -> ScopeKind.REGION;
                case LOCATION -> ScopeKind.LOCATION;
                case EVENT, DISASTER -> ScopeKind.EVENT;
                case PLAYER_OPERATIONAL -> ScopeKind.PLAYER;
                default -> null;
            };
            if (scope != null) revisions.put(new ScopeRef(scope,state.key().id()),state.header().revision());
        }
        if (state instanceof UniqueAggregate u) unique(u.value());
        if (state instanceof OwnershipAggregate o) {
            var v=o.value();var old=owners.get(v.resourceKey());
            if(old!=null) {
                require(old.header().id().equals(v.header().id()),"ownership identity reset");
                require(v.epoch().compareTo(old.epoch())>=0 && v.playerOverrideEpoch().compareTo(old.playerOverrideEpoch())>=0,"ownership epoch regression");
                if(old.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER && v.disposition()!=OwnershipDisposition.YIELDED_TO_PLAYER)
                    require(v.epoch().compareTo(old.epoch())>0 && Collections.disjoint(old.ownerTokens(),v.ownerTokens()),"cannot revive yielded ownership");
            } owners.put(v.resourceKey(),v);
        }
        if (record instanceof DurablePrepare d) {
            var p=d.value();require(prepares.putIfAbsent(p.transactionId(),p)==null,"duplicate PREPARE");
            for(var u:p.uniqueness())add(new DurableUnique(u),seq,hash);
        }
        if (record instanceof DurableMemoryDelta d) {
            var v = d.value();
            require(revisions.getOrDefault(v.scope(), new Revision(0)).equals(v.beforeRevision()), "memory CAS");
            require(v.afterRevision().equals(v.beforeRevision().plus(1)), "memory revision");
            revisions.put(v.scope(), v.afterRevision());
        }
        if (record instanceof DurableFact d) fact(d.value());
        if (record instanceof DurableOutbox d) message(d.value());
        if (record instanceof DurableResult d) {
            var p=prepares.get(d.value().prepareTransactionId());
            require(p!=null && p.operationToken().equals(d.value().operationToken()),"result without matching durable intent");
            require(results.add(d.value().operationToken()),"operation result already committed");
            d.value().facts().forEach(this::fact); d.value().outbox().forEach(this::message);
        }
        if (record instanceof DurableRequest d) {
            ProducerKey key = new ProducerKey(d.original().producerId(), d.original().producerKey());
            var identity = new RequestIdentity(d.idempotenceHash(), d.schedulingPolicyHash());
            var old = requestKeys.putIfAbsent(key, identity);
            require(old == null || old.equals(identity), "IDEMPOTENCY_CONFLICT");
        }
        if (record instanceof DurableInbox d) {
            var v = d.value(); var key = new InboxKey(v.consumerId(), v.messageId());
            var old = inbox.get(key);
            require(old == null, "inbox already consumed; replay original transaction only");
            require(v.contiguousCursor().value() >= inboxCursors.getOrDefault(v.consumerId(), 0L), "cursor regression");
            inbox.put(key, v); inboxCursors.put(v.consumerId(), v.contiguousCursor().value());
        }
        entries.add(new Entry(seq, record));
    }
    private void unique(UniqueUseRecord v) {
        var key=new UniqueKey(v.definitionId(),v.scopeKind(),v.canonicalScopeId());var old=unique.get(key);
        if(v.status()==UniqueStatus.RESERVED)for(UUID ancestor:v.originScopeIds()) {
            var inherited=unique.get(new UniqueKey(v.definitionId(),v.scopeKind(),ancestor));
            require(inherited==null || inherited.status()==UniqueStatus.RELEASED_BEFORE_EFFECT
                || inherited.header().id().equals(v.header().id()),"inherited uniqueness already reserved/spent");
        }
        if(old!=null) {
            require(old.header().id().equals(v.header().id()),"uniqueness identity reset");
            require(v.header().revision().equals(old.header().revision().plus(1)),"uniqueness revision CAS");
            if(old.status()==UniqueStatus.SPENT_CONFIRMED || old.status()==UniqueStatus.SPENT_POSSIBLE)
                require(v.status()==UniqueStatus.SPENT_CONFIRMED || v.status()==UniqueStatus.SPENT_POSSIBLE,"spent uniqueness cannot reset");
            if(old.status()==UniqueStatus.RESERVED && v.status()==UniqueStatus.RESERVED)
                require(old.instanceId().equals(v.instanceId()),"uniqueness already reserved");
        } unique.put(key,v);
    }
    private void fact(DirectorFact f) {
        var old = facts.putIfAbsent(f.factId(), f); require(old == null || old.equals(f), "immutable fact conflict");
    }
    private void message(DirectorEnvelope m) {
        require(!messages.containsKey(m.messageId()), "duplicate outbox message");
        require(m.worldSequence().orElseThrow().value() == Math.incrementExact(messageSequence), "outbox sequence");
        messageSequence++; messages.put(m.messageId(), m); outbox.put(messageSequence,m);
        if (m.payload() instanceof FactPayload f) fact(f.value());
    }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
