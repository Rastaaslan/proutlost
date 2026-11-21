package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Moves the SAME explicitly tagged display entity; never copies a stack or inventory.
 * Exact canonical NBT and identity CAS are checked again immediately before movement.
 * Other entity types require their own registered codec; no decorative heuristic is used.
 */
public final class DecorativeBinding implements ServerEffectPort.Binding {
    private static final ResourceId CODEC=new ResourceId("proutlost_director:decorative_display_nbt");
    private static final ResourceId OWNER_CODEC=new ResourceId("proutlost_director:owner_v1");
    private static final TagKey<EntityType<?>> MOVABLE=TagKey.create(Registries.ENTITY_TYPE,ResourceLocation.fromNamespaceAndPath("proutlost_director","reversible_decorative"));
    private final Function<DimensionId,ServerLevel> levels;private final Supplier<OwnershipView> ownership;private final int maxBytes;
    public DecorativeBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,int maxBytes){
        this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);if(maxBytes<128||maxBytes>BlobContent.MAX_BYTES)throw new IllegalArgumentException("NBT bound");this.maxBytes=maxBytes;
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    // No J02-approved decorative primitive ID yet: keep this binding unavailable.
    public Set<PrimitiveId> supportedPrimitives(){return Set.of();}
    public Set<CapabilityId> capabilities(){return Set.of(new CapabilityId("object.decorative_move_v1"));}
    private ServerLevel level(DimensionId d){ServerLevel l=Objects.requireNonNull(levels.apply(d),"dimension unavailable");if(!l.getServer().isSameThread())throw new IllegalStateException("server actor required");if(!l.dimension().location().toString().equals(d.value()))throw new IllegalArgumentException("dimension");return l;}
    private DecorativeMove args(EffectIntent i){if(!(i.typedArgs() instanceof DecorativeMove d)||i.version()!=1||d.object().domain()!=OwnershipDomain.DECORATIVE_OBJECT||!d.object().dimension().equals(d.destination().dimension()))throw new IllegalArgumentException("decorative args");return d;}
    private Entity entity(DecorativeMove args){
        Entity e=level(args.object().dimension()).getEntity(UUID.fromString(args.object().key()));
        if(!(e instanceof Display) || !e.getType().is(MOVABLE) || e.isRemoved() || e.isPassenger() || e.isVehicle())throw new IllegalArgumentException("unknown or unavailable decorative codec");return e;
    }
    private BlobContent capture(Entity e){CompoundTag tag=new CompoundTag();e.saveWithoutId(tag);return blob(tag);}
    private BlobContent blob(CompoundTag tag){
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(Tag.TAG_COMPOUND);out.writeUTF("");write(tag,out);
            if(bytes.size()>maxBytes)throw new IllegalArgumentException("decorative NBT too large");return BlobContent.capture(CODEC,1,bytes.toByteArray());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static void write(Tag tag,DataOutput out)throws IOException {
        if(tag instanceof CompoundTag compound){for(String key:compound.getAllKeys().stream().sorted().toList()){Tag child=Objects.requireNonNull(compound.get(key));out.writeByte(child.getId());out.writeUTF(key);write(child,out);}out.writeByte(Tag.TAG_END);}
        else if(tag instanceof ListTag list){out.writeByte(list.getElementType());out.writeInt(list.size());for(Tag child:list)write(child,out);}
        else tag.write(out);
    }
    private CompoundTag decode(BlobContent content){
        if(!content.reference().codecId().equals(CODEC)||content.reference().codecVersion()!=1||content.reference().bytes()>maxBytes)throw new IllegalArgumentException("decorative codec");
        try{return NbtIo.read(new DataInputStream(new ByteArrayInputStream(content.bytes())),NbtAccounter.create(maxBytes));}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    private static BlobContent find(BlobBundle blobs,BlobRef ref){return blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();}
    private static OwnershipRecord capturedOwner(PreparedEffect plan,BlobBundle blobs,ResourceKey key){
        var found=plan.durableBlobs().snapshots().stream()
                .filter(r->r.codecId().equals(OWNER_CODEC)&&r.codecVersion()==1)
                .map(r->new ModelCodec<>(OwnershipRecord.class).decode(find(blobs,r).bytes()))
                .filter(record->record.resourceKey().equals(key)).toList();
        if(found.size()!=1)throw new IllegalArgumentException("missing/ambiguous durable decorative owner");
        return found.getFirst();
    }
    private boolean safe(Entity e,DecorativeMove args,Footprint footprint){
        ServerLevel l=level(args.object().dimension());var dest=args.destination();
        if(!footprint.dimension().equals(dest.dimension()) || !footprint.cells().contains(dest)
                || footprint.loadedChunks().stream().anyMatch(c->l.getChunkSource().getChunkNow(c.x(),c.z())==null)
                || l.getChunkSource().getChunkNow(dest.x()>>4,dest.z()>>4)==null || dest.y()<l.getMinBuildHeight() || dest.y()>=l.getMaxBuildHeight())return false;
        var box=e.getBoundingBox().move(dest.x()+.5-e.getX(),dest.y()-e.getY(),dest.z()+.5-e.getZ());
        return l.getWorldBorder().isWithinBounds(box) && l.noCollision(e,box);
    }
    public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
        DecorativeMove args=args(intent);Entity e=entity(args);if(!safe(e,args,intent.expectedFootprint()))throw new IllegalArgumentException("unsafe destination");
        OwnershipRecord owner=Objects.requireNonNull(context.ownership().records().get(args.object()),"decorative ownership not admitted");
        if(owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE||owner.ownerTokens().isEmpty()
                ||!owner.resourceKey().equals(args.object()))throw new IllegalArgumentException("decorative ownership");
        var ownerBlob=BlobContent.capture(OWNER_CODEC,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));
        var before=capture(e);if(!before.reference().hash().equals(args.expectedComponents()))throw new IllegalArgumentException("decorative components changed");
        CompoundTag post=decode(before);ListTag position=new ListTag();position.add(DoubleTag.valueOf(args.destination().x()+.5));position.add(DoubleTag.valueOf(args.destination().y()));position.add(DoubleTag.valueOf(args.destination().z()+.5));post.put("Pos",position);
        var after=blob(post);
        // A move to the existing state cannot consume a durable J04 permit.
        if (!DecorativeOwnershipFence.changesState(before.reference().hash(),after.reference().hash()))
            throw new IllegalArgumentException("no-op decorative move");
        var read=new ReadSet(context.snapshot().scopeRevisions(),Map.of(args.object(),owner.epoch()),Map.of(args.object(),before.reference().hash()));
        var budget=new BudgetVector(0,0,0,0,0,1,0,0,0,0);
        var refs=new EffectBlobs(Set.of(before.reference(),ownerBlob.reference()),after.reference(),before.reference(),Map.of(args.object(),before.reference()),Map.of(args.object(),after.reference()));
        var plan=new PreparedEffect(intent,context.snapshot(),List.of(new ResourceClaim(args.object(),ClaimPolicy.EXCLUSIVE,budget,Optional.empty(),owner.epoch())),read,Map.of(args.object(),after.reference().hash()),budget,List.of(),capabilities(),refs);
        var hydrated=java.util.stream.Stream.of(before,after,ownerBlob).distinct().toList();
        return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(hydrated));
    }
    public boolean revalidate(PreparedEffect plan,BlobBundle blobs){
        var args=args(plan.intent());var record=ownership.get().records().get(args.object());
        var captured=capturedOwner(plan,blobs,args.object());
        if(!DecorativeOwnershipFence.sameOwner(captured,record)
                ||!captured.epoch().equals(plan.preconditions().epochs().get(args.object())))return false;
        Entity e=entity(args);return safe(e,args,plan.intent().expectedFootprint())&&capture(e).reference().hash().equals(plan.preconditions().fingerprints().get(args.object()));
    }
    public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
        if(!revalidate(plan,blobs))throw new IllegalStateException("decorative object yielded or changed");var args=args(plan.intent());Entity e=entity(args);
        // Validate captured identity in addition to the fresh world hash before touching position.
        CompoundTag target=decode(find(blobs,plan.durableBlobs().expectedPostStates().get(args.object())));
        if(!target.getUUID("UUID").equals(e.getUUID()))throw new IllegalArgumentException("decorative identity substitution");
        ListTag pos=target.getList("Pos",Tag.TAG_DOUBLE);
        if(pos.size()!=3 || pos.getDouble(0)!=args.destination().x()+.5 || pos.getDouble(1)!=args.destination().y() || pos.getDouble(2)!=args.destination().z()+.5)throw new IllegalArgumentException("decorative position substitution");
        e.setPos(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2));var hash=capture(e).reference().hash();
        return new SliceResult(hash.equals(plan.expectedPost().get(args.object()))?Outcome.SUCCEEDED:Outcome.PARTIAL,Set.of(plan.intent().operationToken()),Map.of(args.object(),hash),0,Set.of(),Set.of(),List.of());
    }
    public ReconcileResult reconcile(RecoveryProbe probe){
        var plan=probe.durableIntent();var args=args(plan.intent());var record=ownership.get().records().get(args.object());
        if(record==null)return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
        var captured=capturedOwner(plan,probe.blobs(),args.object());
        if(record.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER
                ||!record.ownerTokens().equals(captured.ownerTokens())
                ||!record.epoch().equals(captured.epoch())
                ||!record.playerOverrideEpoch().equals(captured.playerOverrideEpoch()))
            return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
        if(!DecorativeOwnershipFence.sameOwner(captured,record)
                ||!record.epoch().equals(plan.preconditions().epochs().get(args.object())))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        final Fingerprint hash;
        try{hash=capture(entity(args)).reference().hash();}
        catch(IllegalArgumentException unavailable){return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());}
        // Do not choose a favorable first proof while conflicting physical
        // evidence for the same decorative object is also present.
        var candidates=probe.loadedProofs().stream()
                .filter(p->p.resource().equals(args.object())).toList();
        if(candidates.size()!=1 || !DecorativeOwnershipFence.confirmedAtCurrentOwner(
                candidates.getFirst(),captured,record,hash))
            return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
        var proof=candidates.getFirst();
        var status=hash.equals(plan.expectedPost().get(args.object()))?ReconcileStatus.SAME_POST:
                hash.equals(plan.preconditions().fingerprints().get(args.object()))&&proof.localAbsenceProven()?ReconcileStatus.SAME_PRE_PROVEN:ReconcileStatus.AMBIGUOUS;
        return new ReconcileResult(status,probe.loadedProofs(),List.of());
    }
}
