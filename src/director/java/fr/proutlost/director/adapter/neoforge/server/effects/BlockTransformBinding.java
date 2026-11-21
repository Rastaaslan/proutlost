package fr.proutlost.director.adapter.neoforge.server.effects;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;
import java.util.function.*;

/** Real bounded block replacements for explicitly registered, capturable recipes.
 * No block entities, fluids, falling blocks or guessed external-mod inverses are accepted.
 * Local provenance is updated in the same actor turn. Full effects still require J04 qualification.
 */
public final class BlockTransformBinding implements ServerEffectPort.Binding {
    public record Recipe(Map<ResourceId,ResourceId> replacements,boolean constructionImpactAllowed) {
        public Recipe { replacements=Map.copyOf(replacements);if(replacements.isEmpty())throw new IllegalArgumentException("empty recipe"); }
    }
    public static final ResourceId STATE_CODEC=new ResourceId("proutlost_director:block_state_v1");
    private static final ResourceId OWNER_CODEC=new ResourceId("proutlost_director:owner_v1");
    private final Function<DimensionId,ServerLevel> levels;private final Supplier<OwnershipView> ownership;
    private final BiConsumer<BlockPosition,OwnershipRecord> localLedger;private final Supplier<Tick> clock;
    private final Map<ResourceId,Recipe> recipes;private final int maximumCells;
    private final PrimitiveId primitive;private final Set<CapabilityId> providedCapabilities;
    public BlockTransformBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,
                                  BiConsumer<BlockPosition,OwnershipRecord> localLedger,Supplier<Tick> clock,
                                  Map<ResourceId,Recipe> recipes,int maximumCells){
        this(levels,ownership,localLedger,clock,recipes,maximumCells,new PrimitiveId("proutlost:camp_growth"),
            Set.of(new CapabilityId("mutation.plants_v1")));
    }
    public BlockTransformBinding(Function<DimensionId,ServerLevel> levels,Supplier<OwnershipView> ownership,
                                  BiConsumer<BlockPosition,OwnershipRecord> localLedger,Supplier<Tick> clock,
                                  Map<ResourceId,Recipe> recipes,int maximumCells,PrimitiveId primitive,
                                  Set<CapabilityId> providedCapabilities){
        this.primitive=Objects.requireNonNull(primitive);this.providedCapabilities=Set.copyOf(providedCapabilities);
        this.levels=Objects.requireNonNull(levels);this.ownership=Objects.requireNonNull(ownership);this.localLedger=Objects.requireNonNull(localLedger);this.clock=Objects.requireNonNull(clock);
        this.recipes=Map.copyOf(recipes);if(maximumCells<1||maximumCells>64)throw new IllegalArgumentException("slice cell capacity");this.maximumCells=maximumCells;
    }
    public ProviderMode mode(){return ProviderMode.PRODUCTION;}
    public Set<PrimitiveId> supportedPrimitives(){return Set.of(primitive);}
    public Set<CapabilityId> capabilities(){return providedCapabilities;}
    private ServerLevel level(DimensionId d){var l=Objects.requireNonNull(levels.apply(d),"dimension unavailable");if(!l.getServer().isSameThread())throw new IllegalStateException("server actor required");if(!l.dimension().location().toString().equals(d.value()))throw new IllegalArgumentException("dimension");return l;}
    private static BlockPos pos(BlockPosition p){return new BlockPos(p.x(),p.y(),p.z());}
    private static ResourceKey key(BlockPosition p){return new ResourceKey(OwnershipDomain.BLOCK,p.dimension(),p.x()+","+p.y()+","+p.z());}
    private static boolean safeState(BlockState state){return !state.hasBlockEntity()&&state.getFluidState().isEmpty()&&!(state.getBlock() instanceof FallingBlock)&&!(state.getBlock() instanceof BaseFireBlock);}
    public static BlobContent stateBlob(BlockState state){
        Map<String,JsonValue> properties=new TreeMap<>();state.getValues().forEach((property,value)->properties.put(property.getName(),new JsonValue.Text(propertyName(property,value))));
        return BlobContent.capture(STATE_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of("block",new JsonValue.Text(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),"properties",new JsonValue.ObjectValue(properties)))));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static String propertyName(Property property,Comparable value){return property.getName(value);}
    private static BlockState decode(BlobContent blob){
        if(!blob.reference().codecId().equals(STATE_CODEC)||blob.reference().codecVersion()!=1)throw new IllegalArgumentException("block codec");
        var root=((JsonValue.ObjectValue)CanonicalJsonV1.decode(blob.bytes())).values();if(!root.keySet().equals(Set.of("block","properties")))throw new IllegalArgumentException("block fields");
        var id=ResourceLocation.parse(((JsonValue.Text)root.get("block")).value());Block block=BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(()->new IllegalArgumentException("registry block absent"));
        var properties=((JsonValue.ObjectValue)root.get("properties")).values();
        if(!properties.keySet().equals(block.getStateDefinition().getProperties().stream().map(Property::getName).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("block properties");
        BlockState state=block.defaultBlockState();for(var entry:properties.entrySet())state=set(state,Objects.requireNonNull(block.getStateDefinition().getProperty(entry.getKey())),((JsonValue.Text)entry.getValue()).value());
        return state;
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state,Property<T> property,String value){return state.setValue(property,property.getValue(value).orElseThrow(()->new IllegalArgumentException("block property value")));}
    private List<BlockPosition> cells(EffectIntent intent){
        if(intent.expectedFootprint().cells().isEmpty()||intent.expectedFootprint().cells().size()>maximumCells)throw new IllegalArgumentException("bounded cell slice required");
        return intent.expectedFootprint().cells().stream().sorted(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z)).toList();
    }
    private boolean loaded(ServerLevel level,BlockPosition p){return level.getChunkSource().getChunkNow(p.x()>>4,p.z()>>4)!=null&&p.y()>=level.getMinBuildHeight()&&p.y()<level.getMaxBuildHeight()&&level.getWorldBorder().isWithinBounds(pos(p));}
    private static BlobContent find(BlobBundle blobs,BlobRef ref){return blobs.contents().stream().filter(b->b.reference().equals(ref)).findFirst().orElseThrow();}
    private static OwnershipRecord capturedOwner(PreparedEffect plan,BlobBundle blobs,ResourceKey key){
        var owners=plan.durableBlobs().snapshots().stream()
                .filter(r->r.codecId().equals(OWNER_CODEC)&&r.codecVersion()==1)
                .map(r->new ModelCodec<>(OwnershipRecord.class).decode(find(blobs,r).bytes()))
                .filter(r->r.resourceKey().equals(key)).toList();
        // Duplicate owner snapshots for the same physical cell are ambiguous,
        // not an arbitrary first match from an unordered WAL snapshot set.
        if(owners.size()!=1)throw new IllegalArgumentException("missing/ambiguous durable block owner");
        return owners.getFirst();
    }
    public EffectPreparation capture(EffectIntent intent,PlanningSnapshot context){
        if(intent.version()!=1||!intent.primitive().equals(primitive))throw new IllegalArgumentException("registered block recipe required");
        ResourceId recipeId;long maxBlocks;
        if(intent.typedArgs() instanceof CampGrowth args&&primitive.value().equals("proutlost:camp_growth")){
            recipeId=args.recipe();maxBlocks=args.maxBlocks();
        }else if(intent.typedArgs() instanceof Landslide args&&primitive.value().equals("proutlost:landslide")){
            recipeId=args.recipe();maxBlocks=args.maxBlocks();
        }else throw new IllegalArgumentException("typed block recipe mismatch");
        Recipe recipe=Objects.requireNonNull(recipes.get(recipeId),"recipe unavailable");var cells=cells(intent);
        if(cells.size()>maxBlocks)throw new IllegalArgumentException("recipe declared work");ServerLevel level=level(intent.expectedFootprint().dimension());
        Map<BlockPosition,TerrainCellSnapshot> terrain=new HashMap<>();context.snapshot().context().terrain().forEach(t->terrain.putAll(t.cells()));
        Map<ResourceKey,BlobRef> pre=new HashMap<>(),post=new HashMap<>();Map<ResourceKey,Fingerprint> fingerprints=new HashMap<>(),expected=new HashMap<>();Map<ResourceKey,Epoch> epochs=new HashMap<>();Set<BlobRef> snapshots=new HashSet<>();Map<BlobRef,BlobContent> blobs=new HashMap<>();List<ResourceClaim> claims=new ArrayList<>();
        for(var p:cells){
            TerrainCellSnapshot known=Objects.requireNonNull(terrain.get(p),"terrain unknown");
            if(!known.knownContent()||known.protectedContent()||!known.hazards().isEmpty()||!known.codec().equals(STATE_CODEC)
                    || !recipe.constructionImpactAllowed()&&known.tags().contains(new TagId("proutlost:construction")) || !loaded(level,p))throw new IllegalArgumentException("uncapturable/protected terrain");
            BlockState before=level.getBlockState(pos(p));if(!safeState(before))throw new IllegalArgumentException("unknown block behavior");
            ResourceId afterId=Objects.requireNonNull(recipe.replacements().get(new ResourceId(BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString())),"unregistered source state");
            BlockState after=BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(afterId.value())).orElseThrow().defaultBlockState();if(!safeState(after))throw new IllegalArgumentException("unsafe destination behavior");
            var preBlob=stateBlob(before);var postBlob=stateBlob(after);
            // A recipe that leaves the captured cell unchanged cannot consume a
            // durable permit as though it performed a physical mutation.
            if(preBlob.reference().hash().equals(postBlob.reference().hash()))
                throw new IllegalArgumentException("no-op block replacement");
            var key=key(p);var owner=Objects.requireNonNull(context.ownership().records().get(key),"ownership not admitted");
            if(!preBlob.reference().hash().equals(known.stateFingerprint()) || owner.disposition()!=OwnershipDisposition.DIRECTOR_ACTIVE || !owner.epoch().equals(known.ownershipEpoch()) || !owner.playerOverrideEpoch().equals(known.playerOverrideEpoch()))throw new IllegalArgumentException("stale terrain ownership");
            var ownerBlob=BlobContent.capture(OWNER_CODEC,1,new ModelCodec<>(OwnershipRecord.class).encode(owner));snapshots.add(ownerBlob.reference());
            for(var b:List.of(preBlob,postBlob,ownerBlob))blobs.put(b.reference(),b);
            pre.put(key,preBlob.reference());post.put(key,postBlob.reference());fingerprints.put(key,preBlob.reference().hash());expected.put(key,postBlob.reference().hash());epochs.put(key,owner.epoch());
            claims.add(new ResourceClaim(key,ClaimPolicy.EXCLUSIVE,new BudgetVector(0,0,0,0,0,1,0,0,0,0),Optional.empty(),owner.epoch()));
        }
        // The intent blob binds recipe/version/footprint; per-cell pre-state blobs form the inverse.
        var planBlob=BlobContent.capture(new ResourceId("proutlost_director:block_intent"),1,new ModelCodec<>(EffectIntent.class).encode(intent));blobs.put(planBlob.reference(),planBlob);
        var read=new ReadSet(context.snapshot().scopeRevisions(),epochs,fingerprints);var budget=new BudgetVector(0,0,0,0,0,cells.size(),0,0,0,0);
        var refs=new EffectBlobs(snapshots,planBlob.reference(),planBlob.reference(),pre,post);
        var plan=new PreparedEffect(intent,context.snapshot(),claims,read,expected,budget,List.of(),capabilities(),refs);
        return new EffectPreparation(intent,new ValidationResult(true,List.of(),read),Optional.of(plan),new BlobBundle(blobs.values().stream().sorted(Comparator.comparing(b->b.reference().hash().value())).toList()));
    }
    private boolean cellValid(PreparedEffect plan,BlobBundle blobs,BlockPosition p){
        ServerLevel level=level(p.dimension());if(!loaded(level,p))return false;var key=key(p);var owner=ownership.get().records().get(key);var captured=capturedOwner(plan,blobs,key);
        // The complete captured PRE owner must still be current: a rotated
        // token, owner revision or provenance hash cannot reuse a J04 plan.
        return BlockOwnershipFence.exactPre(captured,owner)
                &&stateBlob(level.getBlockState(pos(p))).reference().hash().equals(plan.preconditions().fingerprints().get(key));
    }
    public boolean revalidate(PreparedEffect plan,BlobBundle blobs){return cells(plan.intent()).stream().allMatch(p->cellValid(plan,blobs,p));}
    public SliceResult apply(PreparedEffect plan,BlobBundle blobs){
        List<BlockPosition> cells=cells(plan.intent());Map<ResourceKey,Fingerprint> observed=new HashMap<>();Set<BlockPosition> conflicts=new HashSet<>();int changed=0;
        for(var p:cells){
            if(!cellValid(plan,blobs,p)){conflicts.add(p);break;}var key=key(p);ServerLevel level=level(p.dimension());var before=ownership.get().records().get(key);
            BlockState target=decode(find(blobs,plan.durableBlobs().expectedPostStates().get(key)));if(!safeState(target))throw new IllegalArgumentException("unsafe hydrated state");
            // Vanilla may refuse a placement (including a plugin/mod interception).
            // The consumed J04 permit must not be reported as a successful effect.
            if(!level.setBlock(pos(p),target,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE|Block.UPDATE_SUPPRESS_DROPS)){
                conflicts.add(p);break;
            }
            changed++;
            var actual=stateBlob(level.getBlockState(pos(p))).reference().hash();observed.put(key,actual);
            var current=ownership.get().records().get(key);
            if(!before.equals(current)||!actual.equals(plan.expectedPost().get(key))){conflicts.add(p);break;}
            Tick tick=clock.get();var header=new AggregateHeader(1,before.header().id(),before.header().revision().plus(1),before.header().lastSequence(),tick);
            localLedger.accept(p,new OwnershipRecord(header,key,before.ownerTokens(),before.epoch(),before.disposition(),before.creationMutationId(),actual,before.playerOverrideEpoch(),before.exceptionContractId()));
        }
        return new SliceResult(conflicts.isEmpty()?Outcome.SUCCEEDED:Outcome.PARTIAL,changed>0?Set.of(plan.intent().operationToken()):Set.of(),observed,cells.size()-changed,Set.of(),conflicts,List.of());
    }
    public ReconcileResult reconcile(RecoveryProbe probe){
        var plan=probe.durableIntent();boolean allPost=true,allPre=true;
        for(var p:cells(plan.intent())){
            var key=key(p);ServerLevel level=level(p.dimension());if(!loaded(level,p))return new ReconcileResult(ReconcileStatus.UNAVAILABLE,probe.loadedProofs(),List.of());
            var current=ownership.get().records().get(key);var captured=capturedOwner(plan,probe.blobs(),key);
            if(current==null)return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
            if(current.disposition()==OwnershipDisposition.YIELDED_TO_PLAYER||!current.epoch().equals(captured.epoch())||!current.playerOverrideEpoch().equals(captured.playerOverrideEpoch()))return new ReconcileResult(ReconcileStatus.PLAYER_OVERRIDE,probe.loadedProofs(),List.of());
            var pre=plan.preconditions().fingerprints().get(key);
            var post=plan.expectedPost().get(key);
            if(!BlockOwnershipFence.exactPre(captured,current)
                    && !BlockOwnershipFence.exactPost(captured,current,post))
                return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
            Fingerprint hash=stateBlob(level.getBlockState(pos(p))).reference().hash();
            // Inspect ALL evidence for this resource. A valid proof plus an
            // incompatible one is not proof of a uniquely known physical state.
            var candidates=probe.loadedProofs().stream().filter(r->r.resource().equals(key)).toList();
            if(candidates.size()!=1 || !BlockOwnershipFence.confirmedAtCurrentOwner(
                    candidates.getFirst(),captured,current,hash))
                return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
            var proof=candidates.getFirst();
            allPost&=BlockOwnershipFence.exactPost(captured,current,post)&&hash.equals(post);
            allPre&=BlockOwnershipFence.exactPre(captured,current)&&hash.equals(pre)
                    &&proof.localAbsenceProven();
        }
        return new ReconcileResult(allPost?ReconcileStatus.SAME_POST:allPre?ReconcileStatus.SAME_PRE_PROVEN:ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());
    }
}
