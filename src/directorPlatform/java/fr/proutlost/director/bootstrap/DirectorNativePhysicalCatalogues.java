package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.adapter.neoforge.network.DirectorNativeNetworkBootstrap;

import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.PhysicalSiteExecutionGuards;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** World-local authority/config/import/plan owner. All persistence uses the existing
 * J04 writer and Gameplay metadata lane. Disk work runs on its private bounded worker.
 * A persisted configuration is historical data, never a renewed console permission.
 */
public final class DirectorNativePhysicalCatalogues implements PhysicalCatalogRuntimePort,PhysicalSitePinJournalPort,AutoCloseable{
    private record Source(ResourceId world,ResourceId build){}
    private record Provider(PhysicalCatalogIntegrationProvider value,Fingerprint sha){}
    private record Input(String name,String receipt,byte[] source,byte[] adapters,PhysicalCatalogSourceDescriptor description){}
    private record PreparedConfiguration(PhysicalCatalogConfiguration index,Binding binding,List<BlobContent> blobs){}
    private record Configuration(PhysicalCatalogConfiguration index,Binding binding,PhysicalCatalogIntegrationProvider.Integration integration){}
    private record ParsedPin(PhysicalSitePlanIndex index,PhysicalSiteOffer offer,ExecutionPlan plan){}
    private static final String PROVIDER="fr.proutlost.integration.contra.ContraRealDirectorCatalogImportProbe";
    private final MinecraftServer server;private final WorldId world;private final JournalStore store;private final StoreSession session;
    private final DirectorNativeGameplay gameplay;private final Supplier<Optional<Object>> recoveryAuthority;
    private final Function<CommandSourceStack,ActorIdentity> sourceIdentity;private final Function<SessionId,Optional<ActorIdentity>> identity;
    private final Path root;private final PhysicalCatalogIntegrationProvider.Context context;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"director-physical-catalogue-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final CompletableFuture<Optional<Provider>> discovering;
    private Provider provider;private PhysicalMetadataJournal metadata;private PhysicalCatalogJournalAdmission catalogues;
    private PhysicalSiteExecutionGuards.Owner guards;private final PhysicalRouter router=new PhysicalRouter();
    private final Map<Key,Configuration> configurations=new ConcurrentHashMap<>();
    private final Map<Source,PhysicalCatalogIntegrationProvider.Integration> integrations=new ConcurrentHashMap<>();
    private final Map<Source,Fingerprint> descriptorPins=new ConcurrentHashMap<>();
    private final Map<FactId,Binding> compilingBindings=new ConcurrentHashMap<>();
    private final Map<Key,Grant> grants=new LinkedHashMap<>();private final Set<Binding> issued=new HashSet<>();
    private final Map<String,UUID> issuedExternal=new HashMap<>();private final Map<UUID,String> issuedUuids=new HashMap<>();
    private final Map<EventInstanceId,ParsedPin> activePins=new LinkedHashMap<>();private final Map<EventInstanceId,PhysicalSitePlanIndex> knownPins=new HashMap<>();
    private CompletableFuture<Input> preparing;private CommandSourceStack preparationSource;private ActorIdentity preparationActor;
    private CompletableFuture<PreparedConfiguration> completingPreparation;
    private PhysicalCatalogConfiguration pendingConfiguration;private List<BlobContent> pendingConfigurationBytes;
    private boolean ready,failed,closed,guardsRestored;private String diagnostic="DISCOVERING_INSTALLED_PROVIDER";
    private long activePinBytes;private DirectorNativeCatalogueExecution boundExecutor;
    private volatile Map<DefinitionId,DefinitionRef> definitionSnapshot=Map.of();
    private EventInstanceId checkingEvent;private StoreQuery checkingEventQuery;private CompletableFuture<StoreReadResult> checkingEventRead;

    private static final class Grant{
        final Configuration configuration;final ActorIdentity actor;final StoreSession session;final AtomicBoolean active=new AtomicBoolean(true);
        CompletableFuture<Boolean> receipt;Readback receiptFor;
        Grant(Configuration configuration,ActorIdentity actor,StoreSession session){this.configuration=configuration;this.actor=actor;this.session=session;}
    }
    public DirectorNativePhysicalCatalogues(MinecraftServer server,WorldId world,JournalStore store,DirectorNativeGameplay gameplay,
            Function<CommandSourceStack,ActorIdentity> sourceIdentity,Function<SessionId,Optional<ActorIdentity>> identity,Supplier<Optional<Object>> recoveryAuthority){
        this.server=Objects.requireNonNull(server);this.world=Objects.requireNonNull(world);this.store=Objects.requireNonNull(store);this.gameplay=Objects.requireNonNull(gameplay);
        this.sourceIdentity=Objects.requireNonNull(sourceIdentity);this.identity=Objects.requireNonNull(identity);this.recoveryAuthority=Objects.requireNonNull(recoveryAuthority);
        actor();session=store.session();root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Map<DimensionId,ImportBounds> dimensions=new HashMap<>();for(var level:server.getAllLevels()){
            var border=level.getWorldBorder();dimensions.put(new DimensionId(level.dimension().location().toString()),new ImportBounds(
                (int)Math.ceil(Math.max(-29999984,border.getMinX())),level.getMinBuildHeight(),(int)Math.ceil(Math.max(-29999984,border.getMinZ())),
                (int)Math.floor(Math.min(29999984,border.getMaxX())),level.getMaxBuildHeight(),(int)Math.floor(Math.min(29999984,border.getMaxZ()))));}
        guards=PhysicalSiteExecutionGuards.open(world,session,this::actor);
        context=new PhysicalCatalogIntegrationProvider.Context(server,world,dimensions,store::session,gameplay::physicalOwnershipSnapshot,this::actor,recoveryAuthority.get(),()->definitionSnapshot,()->metadata==null?List.of():metadata.recordedSites());
        discovering=CompletableFuture.supplyAsync(()->{
            var types=ServiceLoader.load(PhysicalCatalogIntegrationProvider.class).stream().limit(2).toList();
            if(types.isEmpty())return Optional.empty();if(types.size()!=1||!types.getFirst().type().getName().equals(PROVIDER))throw new IllegalArgumentException("Exactly one pinned production physical integration provider required");
            var type=types.getFirst();String path="/"+type.type().getName().replace('.','/')+".class";
            try(var in=type.type().getResourceAsStream(path)){if(in==null)throw new IOException("Physical provider bytecode absent");byte[] bytes=in.readNBytes(262145);
                if(bytes.length>262144)throw new IOException("Physical provider class bound");return Optional.of(new Provider(type.get(),BlobContent.hash(bytes)));}
            catch(IOException e){throw new UncheckedIOException(e);}
        },worker);
    }
    private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("physical catalogue owner actor");}
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private boolean authenticated(SessionId actor){var current=identity.apply(actor);return current.isPresent()&&current.orElseThrow().serverAuthenticated()&&current.orElseThrow().permissionLevel()>=4;}
    private boolean valid(Grant grant){return grant!=null&&grant.active.get()&&!failed&&!closed&&grant.session.equals(store.session())&&!store.readOnlySafe()&&authenticated(grant.actor.session());}
    private Grant grant(Key key){var grant=grants.get(key);return valid(grant)?grant:null;}
    private boolean authorized(SessionId actor){return !failed&&!closed&&session.equals(store.session())&&authenticated(actor);}
    private Map<ScopeRef,Revision> worldCas(){
        actor();var query=new StoreQuery(world,Set.of(new AggregateKey(AggregateKind.WORLD,world.value())),new Sequence(0),Optional.empty(),1);
        var read=store.view(query);ReadContracts.validate(read,query,session);require(read.status()==ReadStatus.AVAILABLE,"actual physical metadata world unavailable");
        var rows=read.page().orElseThrow().aggregates();require(rows.size()==1&&rows.getFirst().state() instanceof WorldAggregate,"actual physical metadata WORLD absent");
        var state=((WorldAggregate)rows.getFirst().state()).value();require(state.worldId().equals(world)&&state.bootEpoch().equals(session.bootEpoch())&&state.storeGeneration().equals(session.storeGeneration()),"physical metadata WORLD session differs");
        return Map.of(new ScopeRef(ScopeKind.WORLD,world.value()),state.header().revision());
    }
    private Map<UUID,String> identities(Binding binding){
        Map<UUID,String> result=new HashMap<>();String prefix=binding.sourceWorld().value()+"\0"+binding.sourceBuild().value()+"\0";
        binding.regions().forEach((id,bound)->result.put(bound.value(),prefix+"region\0"+id.value()));binding.locations().forEach((id,bound)->result.put(bound.value(),prefix+"location\0"+id.value()));
        binding.anchors().forEach(a->result.put(a.directorAnchor().value(),prefix+"anchor\0"+a.sourceLocation().value()+"\0"+a.sourceAnchor().value()));return result;
    }
    private Predicate<UUID> reserved(){
        actor();
        var snapshot=store.metadataSnapshot().orElseThrow(()->new IllegalArgumentException("actual core identity index unavailable"));
        require(snapshot.session().equals(session),"identity index session");
        // UUID existence is an index question, not a requirement to hydrate
        // every metadata body or copy the complete catalogue onto this actor.
        // Check every aggregate kind so an existing non-metadata identity can
        // never be mistaken for an available imported region/location/anchor.
        Set<UUID> players=new HashSet<>();server.getPlayerList().getPlayers().forEach(p->players.add(p.getUUID()));
        players.add(world.value());
        return candidate->{
            require(store.session().equals(session)&&!store.readOnlySafe(),"identity index session revoked");
            if(players.contains(candidate))return true;
            for(var kind:AggregateKind.values()){
                var key=new AggregateKey(kind,candidate);
                if(store.metadataRevision(snapshot,key).value()>0||store.metadataOwner(snapshot,key).isPresent())return true;
            }
            return false;
        };
    }
    private void binding(Binding binding,boolean recovery){
        actor();require(binding.directorWorld().equals(world),"binding Director world");
        if(!recovery)require(issued.contains(binding),"binding was not issued by this actual Director owner");
        var reserved=reserved();for(var entry:identities(binding).entrySet()){
            require(!reserved.test(entry.getKey()),"binding collides with actual core/G0/player identity");
            var owner=issuedUuids.get(entry.getKey());require(owner==null||owner.equals(entry.getValue()),"binding UUID belongs to another physical source");
            var prior=issuedExternal.get(entry.getValue());require(prior==null||prior.equals(entry.getKey()),"physical external identity rebound");
        }
    }
    private void remember(Binding binding){binding(binding,true);identities(binding).forEach((uuid,external)->{issuedUuids.put(uuid,external);issuedExternal.put(external,uuid);});}
    private UUID allocate(String external,Predicate<UUID> reserved){var prior=issuedExternal.get(external);if(prior!=null)return prior;
        for(int i=0;i<64;i++){UUID value=UUID.randomUUID();if(!reserved.test(value)&&!issuedUuids.containsKey(value)){issuedExternal.put(external,value);issuedUuids.put(value,external);return value;}}
        throw new IllegalStateException("Director binding allocation unavailable");}
    private Binding issueDraft(PhysicalCatalogSourceDescriptor d){
        actor();var reserved=reserved();String prefix=d.sourceWorld().value()+"\0"+d.sourceBuild().value()+"\0";
        Map<ResourceId,RegionId> regions=new HashMap<>();d.regions().stream().sorted(Comparator.comparing(ResourceId::value)).forEach(id->regions.put(id,new RegionId(allocate(prefix+"region\0"+id.value(),reserved))));
        Map<ResourceId,LocationId> locations=new HashMap<>();d.locations().stream().sorted(Comparator.comparing(ResourceId::value)).forEach(id->locations.put(id,new LocationId(allocate(prefix+"location\0"+id.value(),reserved))));
        List<AnchorBinding> anchors=d.anchors().stream().sorted(Comparator.comparing((PhysicalCatalogSourceDescriptor.AnchorSource a)->a.location().value()).thenComparing(a->a.anchor().value()))
            .map(a->new AnchorBinding(a.location(),a.anchor(),new AnchorId(allocate(prefix+"anchor\0"+a.location().value()+"\0"+a.anchor().value(),reserved)))).toList();
        return new Binding(world,d.sourceWorld(),d.sourceBuild(),d.canonicalSha(),new Fingerprint("0".repeat(64)),regions,locations,anchors);
    }
    private void approveMetadata(PhysicalMetadataIndex index,Boolean recovering){
        actor();require(PhysicalMetadataFacts.world(index).equals(world),"physical metadata foreign world");
        if(index instanceof PhysicalCatalogConfiguration c){
            if(!configurations.containsKey(c.key())){require(configurations.size()<128,"physical configuration count capacity");
                long retained=configurations.values().stream().mapToLong(v->v.index().binding().bytes()+v.index().adapters().bytes()).sum();
                require(retained+c.binding().bytes()+c.adapters().bytes()<=32L*1024*1024,"physical configuration byte capacity");}
            var body=compilingBindings.get(PhysicalMetadataFacts.id(c));require(body!=null,"actual configuration binding bytes not validated");binding(body,recovering);
            if(!recovering)require(preparationActor!=null&&authenticated(preparationActor.session())&&pendingConfiguration!=null&&pendingConfiguration.equals(c),"configuration author authority revoked");
        }else{
            var p=(PhysicalSitePlanIndex)index;var config=configurations.get(p.pin().catalog().key());require(config!=null&&config.index().binding().equals(p.binding()),"site source binding reference differs");
            if(!recovering)require(grant(p.pin().catalog().key())!=null,"actual site source grant absent/revoked");
            if(!recovering&&!activePins.containsKey(p.pin().event())){
                long size=p.pin().need().bytes()+p.pin().selection().bytes()+p.plan().bytes();
                require(activePins.size()<128&&activePinBytes+size<=32L*1024*1024,"physical plan cache preflight capacity");
            }
        }
    }
    private boolean terminal(EventInstanceId event){
        actor();return store.physicalEventReadback(event,4096)
            .filter(v->v.session().equals(session)&&v.released()).isPresent();
    }
    private PhysicalMetadataJournal.RecoveryUse recoveryUse(PhysicalMetadataIndex index){
        actor();if(index instanceof PhysicalSitePlanIndex p){
            if(terminal(p.pin().event()))return PhysicalMetadataJournal.RecoveryUse.HISTORY;
            if(catalogues==null||!catalogues.ready())return PhysicalMetadataJournal.RecoveryUse.WAIT;
        }
        return PhysicalMetadataJournal.RecoveryUse.HYDRATE;
    }
    private PhysicalMetadataJournal.Prepared compileMetadata(PhysicalMetadataIndex index,Map<BlobRef,BlobContent> bytes){
        if(index instanceof PhysicalCatalogConfiguration c){
            var binding=new ModelCodec<>(Binding.class).decode(bytes.get(c.binding()).bytes());
            require(PhysicalCatalogFacts.bindingDigest(binding).equals(binding.bindingSha())&&binding.bindingSha().equals(c.key().bindingSha()),"configuration actual binding digest");
            var source=provider.value.describe(bytes.get(c.source()).bytes());require(source.canonicalSha().equals(c.key().canonicalSourceSha())&&source.contentDigest().equals(c.key().sourceContentDigest())
                &&source.sourceWorld().equals(c.key().sourceWorld())&&source.sourceBuild().equals(c.key().sourceBuild())&&source.revision().equals(c.key().sourceRevision()),"configuration sealed source differs");
            require(c.providerClassSha().equals(provider.sha),"frozen physical provider binary differs; new isolated intent/config required");
            Source id=new Source(c.key().sourceWorld(),c.key().sourceBuild());var pin=descriptorPins.get(id);require(pin==null||pin.equals(c.adapters().hash()),"source build adapter descriptor changed");
            var priorIntegration=integrations.get(id);var integration=priorIntegration==null?provider.value.create(context,c.key(),bytes.get(c.adapters()).bytes()):priorIntegration;
            compilingBindings.put(PhysicalMetadataFacts.id(c),binding);var configuration=new Configuration(c,binding,integration);
            return new PhysicalMetadataJournal.Prepared(commit->{remember(binding);var prior=descriptorPins.putIfAbsent(id,c.adapters().hash());require(prior==null||prior.equals(c.adapters().hash()),"committed source descriptor changed");
                var old=integrations.putIfAbsent(id,integration);require(old==null||old==integration,"physical source integration changed during configuration IO");configurations.put(c.key(),configuration);});
        }
        var p=(PhysicalSitePlanIndex)index;var offer=new PhysicalSiteOffer(p.pin(),List.of(bytes.get(p.pin().need()),bytes.get(p.pin().selection()),bytes.get(p.plan())));
        var plan=offer.plan();p.pin().requirePlan(plan);var source=configurations.get(p.pin().catalog().key());
        require(source!=null&&source.index().binding().equals(p.binding()),"actual site plan binding configuration differs");
        if(bytes.containsKey(p.pin().catalog().completeSource()))require(bytes.get(p.pin().catalog().completeSource()).reference().equals(source.index().source()),"actual site plan source differs");
        // Preflight supplies the3new plan blobs; the generic post-ACK/recovery
        // read strictly requires all5refs, including the existing source/binding.
        if(bytes.containsKey(p.binding())){var binding=new ModelCodec<>(Binding.class).decode(bytes.get(p.binding()).bytes());require(binding.equals(source.binding()),"actual site plan source binding bytes differ");}
        return new PhysicalMetadataJournal.Prepared(commit->{knownPins.put(p.pin().event(),p);var old=activePins.get(p.pin().event());
            if(commit.recovery()&&terminal(p.pin().event()))return;
            if(old==null){long size=offer.blobs().stream().mapToLong(b->b.reference().bytes()).sum();require(activePins.size()<128&&activePinBytes+size<=32L*1024*1024,"active physical plan cache capacity");activePinBytes+=size;}
            activePins.put(p.pin().event(),new ParsedPin(p,offer,plan));});
    }
    public void tick(){
        actor();if(failed)return;
        try{
            require(session.equals(store.session())&&!store.readOnlySafe(),"physical owner J04 session revoked");
            if(provider==null){if(!discovering.isDone())return;var found=discovering.getNow(null);if(found==null||found.isEmpty()){ready=false;diagnostic="MISSING_REQUIRED_PHYSICAL_PROVIDER";return;}provider=found.orElseThrow();
                metadata=new PhysicalMetadataJournal(store,this::actor,this::approveMetadata,()->gameplay.metadataInputQuiescent(),gameplay::acquireMetadataLane,gameplay::releaseMetadataLane,this::worldCas,worker,this::compileMetadata,this::recoveryUse);
            }
            var executor=gameplay.coordinator().executor();if(executor.isPresent())bindExecutor(executor.orElseThrow());
            metadata.tick();
            if(!metadata.configurationsRecovered()){diagnostic=metadata.diagnostic();return;}
            if(catalogues==null)catalogues=new PhysicalCatalogJournalAdmission(store,this::actor,this::authorized,
                (body,use)->binding(body,use==PhysicalCatalogJournalAdmission.BindingUse.RECOVERY),()->gameplay.metadataInputQuiescent(),gameplay::acquireMetadataLane,gameplay::releaseMetadataLane,
                this::worldCas,worker,document->{var configured=configurations.get(document.key());require(configured!=null,"actual J04 adapter configuration required before catalogue");return configured.integration().compiler().compile(document);});
            catalogues.tick();if(!catalogues.ready()){diagnostic=catalogues.diagnostic();return;}
            if(!metadata.ready()){diagnostic=metadata.diagnostic();return;}
            if(!guardsRestored){for(var p:activePins.values()){if(!gameplay.retainD0LogicalPin(p.index().pin()))return;var resolver=configurations.get(p.index().pin().catalog().key()).integration().resolver();resolver.restore(p.offer());guards.bindRecoveredHistoric(p.index().pin(),p.plan(),resolver,this);}
                guards.recoveryComplete();guardsRestored=true;ready=true;diagnostic="READY_SOURCE_AUTHORIZATION_REQUIRED";}
            if(preparing!=null&&preparing.isDone()&&completingPreparation==null&&pendingConfiguration==null){
                require(preparationActor!=null&&authenticated(preparationActor.session()),"preparation authority revoked");var input=preparing.getNow(null);var draft=issueDraft(input.description());
                completingPreparation=CompletableFuture.supplyAsync(()->{
                    var body=new Binding(world,draft.sourceWorld(),draft.sourceBuild(),draft.canonicalSourceSha(),PhysicalCatalogFacts.bindingDigest(draft),draft.regions(),draft.locations(),draft.anchors());
                    var key=new Key(world,body.sourceWorld(),body.sourceBuild(),input.description().revision(),input.description().contentDigest(),input.description().canonicalSha(),body.bindingSha());
                    var source=BlobContent.capture(PhysicalCatalogFacts.SOURCE_CODEC,1,input.source());var bound=BlobContent.capture(PhysicalCatalogFacts.BINDING_CODEC,1,new ModelCodec<>(Binding.class).encode(body));
                    var adapters=BlobContent.capture(PhysicalMetadataFacts.ADAPTERS_CODEC,1,input.adapters());
                    return new PreparedConfiguration(new PhysicalCatalogConfiguration(1,key,source.reference(),bound.reference(),adapters.reference(),provider.sha,input.receipt()),body,List.of(source,bound,adapters));
                },worker);
            }
            if(completingPreparation!=null&&completingPreparation.isDone()&&pendingConfiguration==null){
                require(preparationActor!=null&&authenticated(preparationActor.session()),"preparation authority revoked after compilation");var built=completingPreparation.getNow(null);
                issued.add(built.binding());pendingConfiguration=built.index();pendingConfigurationBytes=built.blobs();compilingBindings.put(PhysicalMetadataFacts.id(pendingConfiguration),built.binding());
            }
            if(pendingConfiguration!=null){var state=metadata.submit(pendingConfiguration,pendingConfigurationBytes);
                if(state==PhysicalMetadataJournal.State.DURABLE){var configuration=Objects.requireNonNull(configurations.get(pendingConfiguration.key()));
                    var previous=grants.put(pendingConfiguration.key(),new Grant(configuration,preparationActor,session));if(previous!=null)previous.active.set(false);
                    diagnostic="CONFIGURATION_DURABLE_REAL_AUTHORITY_READY_FOR_PRODUCER "+pendingConfiguration.key().canonicalSourceSha().value();
                    pendingConfiguration=null;pendingConfigurationBytes=null;preparing=null;completingPreparation=null;preparationSource=null;preparationActor=null;
                }else if(state==PhysicalMetadataJournal.State.REFUSED||state==PhysicalMetadataJournal.State.FAILED_SAFE)throw new IllegalStateException("physical configuration refused: "+metadata.diagnostic());
            }
            grants.values().removeIf(g->{if(valid(g))return false;g.active.set(false);return true;});
        }catch(RuntimeException failure){failed=true;ready=false;diagnostic="FAILED_SAFE "+failure;grants.values().forEach(g->g.active.set(false));if(guards!=null)guards.close();}
    }
    private byte[] readInput(Path directory,String name,int bound)throws IOException{
        Path file=directory.resolve(name);for(Path parent=file;parent!=null&&parent.startsWith(root);parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IOException("Physical input symlink refused");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>bound)throw new IOException("Physical input regular bounded file required: "+name);
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(bound+1);}if(bytes.length<1||bytes.length>bound)throw new IOException("Physical input changed/oversized");return bytes;
    }
    @Override public String prepare(Object actualSource,String name,String receipt){
        actor();if(!(actualSource instanceof CommandSourceStack source)||source.getServer()!=server||!source.hasPermission(4))throw new IllegalArgumentException("actual OP4 command source required");
        if(!ready||provider==null)return "Physical catalogue owner recovering/provider missing.";if(preparing!=null||pendingConfiguration!=null||metadata.busy())return "Physical catalogue preparation already pending.";
        require(name!=null&&name.matches("[a-z0-9][a-z0-9_-]{0,63}"),"physical owned input name");
        Path output=root.resolve(receipt).normalize();require(!Path.of(receipt).isAbsolute()&&output.startsWith(root)&&receipt.matches("[a-zA-Z0-9_./-]{1,256}")&&!Arrays.asList(receipt.split("/")).contains(".."),"physical receipt relative path");
        ActorIdentity authority=sourceIdentity.apply(source);require(authority.serverAuthenticated()&&authority.permissionLevel()>=4&&authenticated(authority.session()),"actual current Director authority required");
        preparationSource=source;preparationActor=authority;Path directory=root.resolve("director-physical-catalogs").resolve(name);
        preparing=CompletableFuture.supplyAsync(()->{try{byte[] catalog=readInput(directory,"catalog.json",1048576),adapters=readInput(directory,"adapters.json",1048576);
            return new Input(name,receipt,catalog,adapters,provider.value.describe(catalog));}catch(IOException e){throw new UncheckedIOException(e);}},worker);
        diagnostic="PREPARING_ISSUED_BINDING_NO_CATALOGUE_ACK";return diagnostic;
    }
    @Override public boolean failedSafe(){if(!server.isSameThread())throw new IllegalStateException("physical status actor");return failed||closed||!session.equals(store.session())||store.readOnlySafe();}
    @Override public CompletionStage<PhysicalCatalogSourceDescriptor> inspect(BlobContent exactSource){
        actor();require(ready()&&provider!=null&&!failedSafe(),"actual physical decoder recovering/unavailable");
        require(exactSource.reference().codecId().equals(PhysicalCatalogFacts.SOURCE_CODEC)&&exactSource.reference().codecVersion()==1&&exactSource.reference().bytes()>0&&exactSource.reference().bytes()<=1048576,"complete physical input codec/byte bound");
        return CompletableFuture.supplyAsync(()->provider.value.describe(exactSource.bytes()),worker);
    }
    @Override public Optional<PhysicalCatalogRuntimePort.Handle> approved(Fingerprint sha,ResourceId sourceWorld,ResourceId sourceBuild){
        actor();if(!ready||catalogues==null)return Optional.empty();var matches=grants.values().stream().filter(this::valid)
            .filter(g->g.configuration.index().key().canonicalSourceSha().equals(sha)&&g.configuration.index().key().sourceWorld().equals(sourceWorld)&&g.configuration.index().key().sourceBuild().equals(sourceBuild)).toList();
        if(matches.size()!=1)return Optional.empty();return Optional.of(new ApprovedImportImpl(matches.getFirst()));
    }
    private final class ApprovedImportImpl implements PhysicalCatalogRuntimePort.Handle{
        private final Grant grant;ApprovedImportImpl(Grant grant){this.grant=grant;}
        public Key key(){actor();return grant.configuration.index().key();}public Binding binding(){actor();return grant.configuration.binding();}
        public Progress submit(BlobContent source){actor();if(!valid(grant))return new Progress(PhysicalCatalogModels.State.STALE_SESSION,key(),Optional.empty(),"source grant revoked");return catalogues.submit(grant.actor.session(),new Document(key(),binding(),source));}
        public Progress poll(){actor();if(!valid(grant))return new Progress(PhysicalCatalogModels.State.STALE_SESSION,key(),Optional.empty(),"source grant revoked");return catalogues.poll(grant.actor.session(),key());}
        public CompletionStage<BlobReadResult> readSource(Readback readback){actor();if(!stillApproved(readback))return CompletableFuture.completedFuture(new BlobReadResult(AckStatus.REJECTED,List.of(),List.of()));return catalogues.readSource(grant.actor.session(),readback);}
        public <T>CompletionStage<T> readDecodedSource(Readback readback,Function<byte[],T> decoder){actor();return PhysicalCatalogReadbackDecoder.read(readSource(readback),readback,worker,decoder);}
        public boolean stillApproved(Readback readback){actor();if(!valid(grant)||!key().equals(readback.key()))return false;var actual=catalogues.poll(grant.actor.session(),key());return actual.state()==PhysicalCatalogModels.State.DURABLE&&actual.readback().filter(readback::equals).isPresent();}
        public boolean receiptReady(Readback readback,Fingerprint providerSha){
            actor();if(!stillApproved(readback)||!providerSha.equals(provider.sha)||!providerSha.equals(grant.configuration.index().providerClassSha()))return false;
            if(grant.receipt==null){
                var anchor=store.verifiedManifestAnchor().orElseThrow(()->new IllegalStateException("actual verified J04 manifest required for receipt"));
                String identity="J04/"+anchor.storeId()+"/"+session.storeGeneration().value()+"/"+key().canonicalSourceSha().value()+"/"+key().bindingSha().value();
                byte[] raw=CanonicalJsonV1.encode(new JsonValue.ObjectValue(Map.of("version",new JsonValue.Number(java.math.BigDecimal.ONE),"worldId",new JsonValue.Text(key().sourceWorld().value()),
                    "buildId",new JsonValue.Text(key().sourceBuild().value()),"catalogueSha256",new JsonValue.Text(key().canonicalSourceSha().value()),
                    "importerSourceSha256",new JsonValue.Text(providerSha.value()),"runtimeIdentity",new JsonValue.Text(identity),"status",new JsonValue.Text("IMPORTED_REAL_ISOLATED_RUNTIME"))));
                grant.receiptFor=readback;grant.receipt=CompletableFuture.supplyAsync(()->writeReceipt(grant,raw),worker);return false;
            }
            if(!grant.receipt.isDone())return false;require(grant.receiptFor.equals(readback),"physical receipt readback changed");return Boolean.TRUE.equals(grant.receipt.getNow(false))&&stillApproved(readback);
        }
    }
    private boolean writeReceipt(Grant grant,byte[] raw){
        try{if(!grant.active.get())return false;Path file=root.resolve(grant.configuration.index().receiptRelative()).normalize();require(file.startsWith(root),"receipt outside owned world");
            for(Path p=file.getParent();p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("receipt symlink parent");Files.createDirectories(file.getParent());
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){if(Files.isSymbolicLink(file)||Files.size(file)>4096)throw new IOException("receipt unowned/oversized");byte[] existing;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){existing=input.readNBytes(4097);}if(!Arrays.equals(existing,raw))throw new IOException("Existing physical receipt differs; use a new isolated receipt path");return grant.active.get();}
            Path temp=Files.createTempFile(file.getParent(),".director-physical-receipt-",".partial");
            try{try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer bytes=ByteBuffer.wrap(raw);while(bytes.hasRemaining())out.write(bytes);out.force(true);}
                if(!grant.active.get())return false;Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);try(var directory=FileChannel.open(file.getParent(),StandardOpenOption.READ)){directory.force(true);}
            }finally{Files.deleteIfExists(temp);}return grant.active.get();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    @Override public PhysicalSitePinJournalPort.State ensurePinned(PhysicalSiteOffer offer){
        actor();if(!ready||failed)return failed?PhysicalSitePinJournalPort.State.FAILED_SAFE:PhysicalSitePinJournalPort.State.PENDING;
        require(grant(offer.pin().catalog().key())!=null,"actual current source grant required for physical event plan");
        var config=Objects.requireNonNull(configurations.get(offer.pin().catalog().key()));var index=new PhysicalSitePlanIndex(1,offer.pin(),offer.planBlob().reference(),config.index().binding());
        var state=metadata.submit(index,offer.blobs());return PhysicalSitePinJournalPort.State.valueOf(state.name());
    }
    @Override public boolean durable(PhysicalSitePin pin){actor();var known=knownPins.get(pin.event());return !failed&&metadata!=null&&metadata.ready()&&catalogues!=null&&catalogues.ready()&&known!=null&&known.pin().equals(pin)&&metadata.state(known)==PhysicalMetadataJournal.State.DURABLE;}
    @Override public void bindForward(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver){actor();require(grant(pin.catalog().key())!=null,"physical forward source grant revoked");guards.bind(pin,plan,resolver,this);}
    @Override public void retire(PhysicalSitePin pin){
        actor();require(terminal(pin.event()),"physical event/result/ownership cleanup not durably proved; retain pin");
        gameplay.retireD0LogicalPin(pin.event());
        var pending=activePins.get(pin.event());
        if(pending!=null){require(pending.index().pin().equals(pin),"physical retirement pin differs");activePins.remove(pin.event());activePinBytes-=pending.offer().blobs().stream().mapToLong(b->b.reference().bytes()).sum();}
        if(guards!=null)guards.retire(pin.event());
    }
    public void bindExecutor(DirectorNativeCatalogueExecution executor){actor();Objects.requireNonNull(executor);if(boundExecutor==executor)return;
        require(boundExecutor==null,"physical execution owner cannot be replaced");
        var refs=new HashMap<DefinitionId,DefinitionRef>();executor.catalog().events().forEach((id,value)->refs.put(id,new DefinitionRef(id,value.contentRevision(),BlobContent.hash(new ModelCodec<>(EventDefinition.class).encode(value)))));
        definitionSnapshot=Map.copyOf(refs);executor.physicalSites(router,this);boundExecutor=executor;}
    public PhysicalSiteResolverPort resolver(){actor();return router;}
    public boolean ready(){actor();return ready&&!failed;}public boolean metadataBusy(){actor();return metadata!=null&&metadata.busy();}
    @Override public String status(){actor();return diagnostic+" ready="+ready()+" world="+world.value()+" configs="+configurations.size()+" grants="+grants.size()+" pins="+activePins.size();}
    @Override public void close(){
        actor();grants.values().forEach(g->g.active.set(false));ready=false;
        try{if(guards!=null)guards.close();if(catalogues!=null)catalogues.closeForRecovery();if(metadata!=null)metadata.closeForRecovery();}
        finally{worker.shutdown();closed=true;}
    }

    private final class PhysicalRouter implements PhysicalSiteResolverPort{
        private final Map<BlobRef,PhysicalSiteResolverPort> selections=new HashMap<>();
        public boolean requires(DefinitionRef definition){actor();return integrations.values().stream().anyMatch(i->i.resolver().requires(definition));}
        public Optional<PhysicalSiteSelection> resolve(Candidate candidate,DecisionInput input,EventInstanceId event,List<PreparedEffect> effects){
            actor();var possible=integrations.values().stream().map(PhysicalCatalogIntegrationProvider.Integration::resolver).filter(r->r.requires(candidate.definition())).distinct().toList();
            require(possible.size()==1,"exactly one authored physical need per actual definition");var resolver=possible.getFirst();var selected=resolver.resolve(candidate,input,event,effects);
            if(selected.isPresent()){var value=selected.orElseThrow();if(grant(value.catalog().key())==null){resolver.discard(value);return Optional.empty();}
                require(selections.size()<128,"physical router selection capacity");selections.put(value.selection().reference(),resolver);}return selected;
        }
        public PhysicalSiteOffer pin(PhysicalSiteSelection selection,ExecutionPlan plan){actor();return Objects.requireNonNull(selections.get(selection.selection().reference())).pin(selection,plan);}
        public void discard(PhysicalSiteSelection selection){actor();var resolver=selections.remove(selection.selection().reference());if(resolver!=null)resolver.discard(selection);}
        public void restore(PhysicalSiteOffer offer){actor();var source=configurations.get(offer.pin().catalog().key());require(source!=null,"actual pinned source configuration absent");source.integration().resolver().restore(offer);selections.put(offer.pin().selection(),source.integration().resolver());}
        public void requireCurrent(PhysicalSitePin pin,ExecutionPlan plan,Optional<EffectIntent> effect){actor();require(grant(pin.catalog().key())!=null,"physical source authority revoked");Objects.requireNonNull(selections.get(pin.selection()),"actual physical selection not restored").requireCurrent(pin,plan,effect);}
        public void retire(PhysicalSitePin pin){actor();var resolver=selections.remove(pin.selection());if(resolver!=null)resolver.retire(pin);}
    }
}
