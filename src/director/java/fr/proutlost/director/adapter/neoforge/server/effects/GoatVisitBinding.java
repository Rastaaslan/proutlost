package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.adapter.neoforge.server.ownership.EntityDirectorAttachment;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.goat.Goat;

/** J05 native goat visit and its explicit inverse. Both require a J04 durable
 * PREPARE permit through ServerEffectPort. The visit is never automatically enabled. */
public final class GoatVisitBinding implements ServerEffectPort.Binding {
    public static final PrimitiveId VISIT=new PrimitiveId("proutlost:goat_visit");
    public static final PrimitiveId DEPART=new PrimitiveId("proutlost:goat_depart");
    public static final CapabilityId GOAT=new CapabilityId("entity.goat_v1");
    private static final ResourceId CODEC_ID=new ResourceId("proutlost_director:goat_slot_v1");
    private static final ModelCodec<EntityOwnershipRecord> CODEC=new ModelCodec<>(EntityOwnershipRecord.class);
    private final Function<DimensionId,ServerLevel> levels;
    private final Function<EffectIntent,EntityOwnershipRecord> slots;
    private final Consumer<EntityOwnershipRecord> changed;

    public GoatVisitBinding(Function<DimensionId,ServerLevel> levels,
            Function<EffectIntent,EntityOwnershipRecord> slots,
            Consumer<EntityOwnershipRecord> changed) {
        this.levels=Objects.requireNonNull(levels);
        this.slots=Objects.requireNonNull(slots);
        this.changed=Objects.requireNonNull(changed);
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(VISIT,DEPART);}
    public Set<CapabilityId> capabilities(){return Set.of(GOAT);}
    private boolean inverse(EffectIntent i){
        if(i.version()!=1 || !(i.typedArgs() instanceof GoatVisit goat) ||
           !goat.role().equals("witness") ||
           !goat.anchorTag().equals(new TagId("proutlost:camp")) ||
           !(i.primitive().equals(VISIT)||i.primitive().equals(DEPART)))
            throw new IllegalArgumentException("unqualified goat visit");
        return i.primitive().equals(DEPART);
    }
    private ServerLevel level(DimensionId dimension){
        var l=Objects.requireNonNull(levels.apply(dimension),"dimension unavailable");
        if(!l.getServer().isSameThread()||!l.dimension().location().toString().equals(dimension.value()))
            throw new IllegalStateException("goat world actor/dimension");
        return l;
    }
    private static BlockPosition position(EffectIntent i){
        if(i.expectedFootprint().cells().size()!=1)throw new IllegalArgumentException("goat one-cell footprint");
        var p=i.expectedFootprint().cells().iterator().next();
        if(!p.dimension().equals(i.expectedFootprint().dimension()) ||
           !i.expectedFootprint().loadedChunks().contains(
               new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)))
            throw new IllegalArgumentException("goat unqualified chunk");
        return p;
    }
    private static BlockPos nativePos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
    private static ResourceKey resource(EntityOwnershipRecord slot){
        return new ResourceKey(OwnershipDomain.ENTITY,slot.lastKnownChunk().dimension(),slot.entityUuid().toString());
    }
    private static BlobContent blob(EntityOwnershipRecord row){
        return BlobContent.capture(CODEC_ID,1,CODEC.encode(row));
    }
    private static EntityOwnershipRecord decode(BlobBundle contents,BlobRef ref){
        if(!ref.codecId().equals(CODEC_ID)||ref.codecVersion()!=1)throw new IllegalArgumentException("goat slot codec");
        return CODEC.decode(contents.contents().stream().filter(c->c.reference().equals(ref))
            .findFirst().orElseThrow().bytes());
    }
    private static EntityOwnershipRecord successor(EntityOwnershipRecord before,boolean inverse,Tick tick){
        var h=before.header();
        return new EntityOwnershipRecord(new AggregateHeader(h.schemaVersion(),h.id(),
            h.revision().plus(1),h.lastSequence(),tick),before.ownerToken(),
            before.entityUuid(),before.instanceId(),before.cohortId(),before.slotId(),
            inverse?EntityPhase.REMOVED:EntityPhase.PRESENT,
            inverse?OwnershipDisposition.RELEASED:OwnershipDisposition.DIRECTOR_ACTIVE,
            before.lastKnownChunk(),tick,before.persistMode(),before.cleanupPolicy(),
            Optional.empty(),before.generation());
    }
    private boolean safeCell(ServerLevel level,BlockPosition p){
        var pos=nativePos(p);
        return level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null
          && pos.getY()>level.getMinBuildHeight() && pos.getY()<level.getMaxBuildHeight()-2
          && level.getWorldBorder().isWithinBounds(pos)
          && level.getBlockState(pos.below()).isSolidRender(level,pos.below())
          && level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()
          && level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty();
    }
    private static boolean lineage(EntityOwnershipRecord before,EntityOwnershipRecord after,boolean inverse){
        return before.header().id().equals(after.header().id())
            && after.header().revision().value()==before.header().revision().value()+1
            && before.ownerToken().equals(after.ownerToken())
            && before.entityUuid().equals(after.entityUuid())
            && before.instanceId().equals(after.instanceId())
            && before.cohortId().equals(after.cohortId())
            && before.slotId().equals(after.slotId())
            && before.lastKnownChunk().equals(after.lastKnownChunk())
            && before.generation().equals(after.generation())
            && before.adoptedBy().isEmpty() && after.adoptedBy().isEmpty()
            && before.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE
            && (inverse ? before.phase()==EntityPhase.PRESENT
                && after.phase()==EntityPhase.REMOVED
                && after.disposition()==OwnershipDisposition.RELEASED
                : before.phase()==EntityPhase.RESERVED
                && after.phase()==EntityPhase.PRESENT
                && after.disposition()==OwnershipDisposition.DIRECTOR_ACTIVE);
    }
    private record Durable(EntityOwnershipRecord before,EntityOwnershipRecord after,ResourceKey key){}
    private Optional<Durable> durable(PreparedEffect plan,BlobBundle blobs){
        try{
            var i=plan.intent();boolean inverse=inverse(i);var p=position(i);
            var refs=plan.durableBlobs();
            if(refs.preStates().size()!=1||refs.expectedPostStates().size()!=1 ||
               !refs.preStates().keySet().equals(refs.expectedPostStates().keySet()) ||
               plan.claims().size()!=1||!plan.capabilities().equals(capabilities()))
                return Optional.empty();
            var key=refs.preStates().keySet().iterator().next();
            var before=decode(blobs,refs.preStates().get(key));
            var after=decode(blobs,refs.expectedPostStates().get(key));
            var claim=plan.claims().getFirst();
            if(!lineage(before,after,inverse)||!before.instanceId().equals(i.instanceId()) ||
               !key.equals(resource(before)) || !key.equals(resource(after)) ||
               !before.lastKnownChunk().equals(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)) ||
               !claim.resource().equals(key)||claim.policy()!=ClaimPolicy.EXCLUSIVE ||
               !claim.expectedEpoch().equals(before.generation()) ||
               !plan.preconditions().epochs().equals(Map.of(key,before.generation())) ||
               !plan.preconditions().fingerprints().get(key).equals(blob(before).reference().hash()) ||
               !plan.expectedPost().equals(Map.of(key,blob(after).reference().hash())) ||
               !refs.snapshots().contains(refs.preStates().get(key)) ||
               !refs.preparedPlan().equals(refs.preStates().get(key)))
                return Optional.empty();
            return Optional.of(new Durable(before,after,key));
        }catch(RuntimeException invalid){return Optional.empty();}
    }
    private Entity liveGoat(ServerLevel level,Durable record){
        var entity=level.getEntity(record.before().entityUuid());
        if(!(entity instanceof Goat goat)||!goat.isAlive()||goat.isLeashed()||
           goat.isPassenger()||goat.isVehicle() ||
           !EntityDirectorAttachment.read(goat).equals(Optional.of(record.before())))
            throw new IllegalStateException("goat owner changed, adopted or unloaded");
        return entity;
    }
    public EffectPreparation capture(EffectIntent i,PlanningSnapshot snapshot){
        boolean inverse=inverse(i);var p=position(i);var level=level(p.dimension());
        var before=Objects.requireNonNull(slots.apply(i),"goat slot must be reserved");
        if(!before.instanceId().equals(i.instanceId()) ||
           !before.lastKnownChunk().equals(new ChunkRef(p.dimension(),p.x()>>4,p.z()>>4)) ||
           !safeCell(level,p))
            throw new IllegalArgumentException("unsafe goat visit cell/slot");
        var visible=level.getEntity(before.entityUuid());
        if(inverse){
            if(!(visible instanceof Goat) ||
               !EntityDirectorAttachment.read(visible).equals(Optional.of(before)) ||
               visible.isPassenger() || visible.isVehicle() ||
               ((Goat)visible).isLeashed())
                throw new IllegalArgumentException("goat adopted/changed before cleanup");
        }else if(visible!=null || before.phase()!=EntityPhase.RESERVED)
            throw new IllegalArgumentException("goat spawn UUID already occupied");
        var cell=snapshot.snapshot().context().terrain().stream()
            .map(t->t.cells().get(p)).filter(Objects::nonNull).findFirst()
            .orElseThrow(()->new IllegalArgumentException("goat habitat not observed"));
        if(!cell.knownContent()||cell.protectedContent()||!cell.hazards().isEmpty())
            throw new IllegalArgumentException("goat unsafe habitat");
        var after=successor(before,inverse,snapshot.snapshot().capturedTick());
        if(!lineage(before,after,inverse))throw new IllegalArgumentException("goat slot transition");
        var pre=blob(before);var post=blob(after);var key=resource(before);
        var read=new ReadSet(snapshot.snapshot().scopeRevisions(),
            Map.of(key,before.generation()),Map.of(key,pre.reference().hash()));
        // Temporary owned entity: one live slot; no block MutationRecord or terrain mutation unit.
        var cost=new BudgetVector(0,0,0,1,0,0,0,0,0,0);
        var refs=new EffectBlobs(Set.of(pre.reference()),pre.reference(),pre.reference(),
            Map.of(key,pre.reference()),Map.of(key,post.reference()));
        var plan=new PreparedEffect(i,snapshot.snapshot(),
            List.of(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,cost,Optional.empty(),before.generation())),
            read,Map.of(key,post.reference().hash()),cost,List.of(),capabilities(),refs);
        return new EffectPreparation(i,new ValidationResult(true,List.of(),read),
            Optional.of(plan),new BlobBundle(List.of(pre,post)));
    }
    public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
        var record=durable(plan,blobs);if(record.isEmpty())return false;
        var row=record.orElseThrow();var i=plan.intent();var p=position(i);
        var level=level(p.dimension());var slot=slots.apply(i);
        if(!row.before().equals(slot)||!safeCell(level,p))return false;
        if(inverse(i)){
            try{liveGoat(level,row);return true;}
            catch(RuntimeException changed){return false;}
        }
        return level.getEntity(row.before().entityUuid())==null;
    }
    public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
        var verified=durable(plan,blobs).orElseThrow(()->new IllegalStateException("goat J04 provenance"));
        if(!revalidate(plan,blobs))throw new IllegalStateException("goat native state changed");
        var p=position(plan.intent());var level=level(p.dimension());
        if(inverse(plan.intent())){
            liveGoat(level,verified).discard();
        }else{
            var created=EntityType.GOAT.create(level);
            if(created==null)throw new IllegalStateException("native goat unavailable");
            created.setUUID(verified.after().entityUuid());
            created.moveTo(p.x()+.5,p.y(),p.z()+.5,0,0);
            created.setPersistenceRequired();
            if(!level.noCollision(created)||!level.getWorldBorder().isWithinBounds(created.getBoundingBox()))
                throw new IllegalArgumentException("goat native spawn obstruction");
            EntityDirectorAttachment.initialize(created,verified.after());
            if(!level.addFreshEntity(created))
                throw new IllegalStateException("goat native spawn refused, reconciliation needed");
        }
        changed.accept(verified.after());
        return new SliceResult(Outcome.SUCCEEDED,Set.of(plan.intent().operationToken()),
            Map.of(verified.key(),blob(verified.after()).reference().hash()),0,
            Set.of(),Set.of(),List.of());
    }
    public ReconcileResult reconcile(RecoveryProbe probe){
        var plan=probe.durableIntent();var row=durable(plan,probe.blobs());
        if(row.isEmpty())return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var record=row.orElseThrow();var p=position(plan.intent());
        var l=level(p.dimension());
        if(!safeCell(l,p))return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
        var entity=l.getEntity(record.before().entityUuid());
        if(entity==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
        if(!(entity instanceof Goat) || !entity.isAlive())
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        Optional<EntityOwnershipRecord> marker;
        try{marker=EntityDirectorAttachment.read(entity);}
        catch(RuntimeException corrupt){return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
        if(marker.isEmpty())return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var owner=marker.orElseThrow();
        if(owner.adoptedBy().isPresent()||owner.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||((Goat)entity).isLeashed())
            return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
        if(!inverse(plan.intent())&&owner.equals(record.after()))
            return new ReconcileResult(ReconcileStatus.SAME_POST,probe.loadedProofs(),List.of());
        return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
    }
}