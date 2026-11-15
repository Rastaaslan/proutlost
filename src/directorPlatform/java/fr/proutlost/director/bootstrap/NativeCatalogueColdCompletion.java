package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.runtime.CatalogueEventLedger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Cold completion metadata only: no observer, player, catalogue, J03 input or apply authority.
 * Definition and metadata keys are sealed with the original native START before apply.
 * A missing legacy pin stays cleanup-only. It is never reconstructed from today's catalogue. */
public final class NativeCatalogueColdCompletion {
 private NativeCatalogueColdCompletion(){}
 private static final ModelCodec<EventDefinition> DEFINITION=new ModelCodec<>(EventDefinition.class);
 private static final ModelCodec<ExecutionPlan> PLAN=new ModelCodec<>(ExecutionPlan.class);
 private static final ModelCodec<AggregateKey> KEY=new ModelCodec<>(AggregateKey.class);
 public record Identity(EventDefinition definition,ExecutionPlan plan,DecisionId decision,
   Optional<ChainId> parentChain,Set<AggregateKey> ledgerKeys){
  public Identity{
   Objects.requireNonNull(decision);Objects.requireNonNull(parentChain);ledgerKeys=Set.copyOf(ledgerKeys);
   new CatalogueEventLedger.Selection(definition,plan);
   if(ledgerKeys.isEmpty()||ledgerKeys.size()>16||
     !ledgerKeys.contains(new AggregateKey(AggregateKind.WORLD,plan.scopes().worldId().value()))||
     ledgerKeys.stream().anyMatch(k->k.kind()!=AggregateKind.WORLD&&k.kind()!=AggregateKind.REGION&&
       k.kind()!=AggregateKind.UNIQUE&&k.kind()!=AggregateKind.COOLDOWN))
    throw new IllegalArgumentException("sealed actual selected ledger key set required");
  }
  public CatalogueEventLedger.Selection selection(){return new CatalogueEventLedger.Selection(definition,plan);}
 }
 public static Identity beforeApply(SelectedEventStart selected,DecisionInput actual){
  var selection=new CatalogueEventLedger.Selection(selected.definition(),selected.plan());
  return new Identity(selected.definition(),selected.plan(),selected.proposal().proposalId(),selected.parentChain(),
    NativeCatalogueLedgerState.keysBeforeApply(selection,actual));
 }
 /** Existing selected_plan/decision/parent fields stay canonical; add only the cold pins. */
 public static void pin(Identity identity,Map<String,JsonValue> payload){
  payload.put("selected_definition",new JsonValue.Text(Base64.getEncoder().encodeToString(DEFINITION.encode(identity.definition()))));
  payload.put("selected_ledger_keys",new JsonValue.Array(identity.ledgerKeys().stream()
    .sorted(Comparator.comparing(AggregateKey::toString))
    .map(k->(JsonValue)new JsonValue.Text(Base64.getEncoder().encodeToString(KEY.encode(k)))).toList()));
 }
 /** Retain the exact encoded DTO bytes without exceeding the G0 bound on one Text.
  * Small legacy values keep their existing representation; large values are ordered ASCII chunks. */
 public static JsonValue encodedBytes(byte[] bytes){
  var encoded=Base64.getEncoder().encodeToString(Objects.requireNonNull(bytes));
  if(encoded.length()<=65536)return new JsonValue.Text(encoded);
  var chunks=new ArrayList<JsonValue>();
  for(int start=0;start<encoded.length();start+=32768)
   chunks.add(new JsonValue.Text(encoded.substring(start,Math.min(start+32768,encoded.length()))));
  return new JsonValue.Array(chunks);
 }
 /** Legacy Text and the new ordered Text array decode to the same original byte sequence. */
 public static byte[] decodedBytes(JsonValue value){
  if(value instanceof JsonValue.Text text)return Base64.getDecoder().decode(text.value());
  if(value instanceof JsonValue.Array array){
   var encoded=new StringBuilder();
   for(var part:array.values()){
    if(!(part instanceof JsonValue.Text text))throw new IllegalArgumentException("encoded bytes require Text chunks");
    encoded.append(text.value());
   }
   return Base64.getDecoder().decode(encoded.toString());
  }
  throw new IllegalArgumentException("encoded bytes require Text or Text array");
 }
 public static Optional<Identity> restore(DirectorFact start){
  var payload=start.payload();var definition=payload.get("selected_definition");var keys=payload.get("selected_ledger_keys");
  var plan=payload.get("selected_plan");
  if(keys==null)return Optional.empty(); // Legacy definition pins alone do not authorize ledger backfill.
  if(!(definition instanceof JsonValue.Text encoded)||!(keys instanceof JsonValue.Array array)||
    plan==null||
    !(payload.get("selected_decision") instanceof JsonValue.Text decision))
   throw new IllegalArgumentException("incomplete selected cold pins");
  var restoredKeys=new HashSet<AggregateKey>();
  for(var value:array.values()){
   if(!(value instanceof JsonValue.Text text)||!restoredKeys.add(KEY.decode(Base64.getDecoder().decode(text.value()))))
    throw new IllegalArgumentException("duplicate/invalid selected cold key");
  }
  Optional<ChainId> parent=payload.get("parent_chain") instanceof JsonValue.Text chain?
    Optional.of(new ChainId(UUID.fromString(chain.value()))):Optional.empty();
  var identity=new Identity(DEFINITION.decode(Base64.getDecoder().decode(encoded.value())),
    PLAN.decode(decodedBytes(plan)),new DecisionId(UUID.fromString(decision.value())),parent,restoredKeys);
  if(!start.eventId().equals(Optional.of(identity.selection().event())))
   throw new IllegalArgumentException("cold START event mismatch");
  return Optional.of(identity);
 }
 public record View(CatalogueEventLedger.Metadata metadata,ReadStamp stamp,Map<AggregateKey,Revision> revisions,
   Map<ScopeRef,Revision> scopeRevisions){
  public View{revisions=Map.copyOf(revisions);scopeRevisions=Map.copyOf(scopeRevisions);}
 }
 /** Called and polled by the server actor; IO futures carry DTOs only. */
 public static final class Read {
  private final JournalStore store;private final Identity identity;
  private StoreSession session;private StoreQuery query;private CompletableFuture<StoreReadResult> pending;
  public Read(JournalStore store,Identity identity){this.store=Objects.requireNonNull(store);this.identity=Objects.requireNonNull(identity);}
  public Optional<View> poll(){
   if(store.readOnlySafe())throw new IllegalStateException("cold ledger writer unavailable");
   if(pending==null){
    session=store.session();
    if(!session.worldId().equals(identity.plan().scopes().worldId()))throw new IllegalStateException("cold ledger world mismatch");
    query=new StoreQuery(session.worldId(),identity.ledgerKeys(),new Sequence(0),Optional.empty(),16);
    pending=store.read(query).toCompletableFuture();return Optional.empty();
   }
   if(!pending.isDone())return Optional.empty();
   var result=pending.join();pending=null;
   if(!session.equals(store.session()))return Optional.empty();
   ReadContracts.validate(result,query,session);
   if(result.status()!=ReadStatus.AVAILABLE)return Optional.empty();
   var page=result.page().orElseThrow();var stamp=result.stamp().orElseThrow();
   if(page.hasMore()||!page.records().isEmpty()||!stamp.session().equals(session)||
     !page.lastDurableSequence().equals(stamp.snapshotSequence())||stamp.snapshotSequence().value()<=0)
    throw new IllegalStateException("cold ledger point read cut/shape mismatch");
   var states=new HashMap<AggregateKey,AggregateState>();
   var revisions=new HashMap<AggregateKey,Revision>();identity.ledgerKeys().forEach(k->revisions.put(k,new Revision(0)));
   for(var row:page.aggregates()){
    var state=row.state();
    if(!identity.ledgerKeys().contains(state.key())||states.putIfAbsent(state.key(),state)!=null||
      row.committedSequence().value()<=0||row.committedSequence().compareTo(stamp.snapshotSequence())>0||
      state.header().lastSequence().compareTo(row.committedSequence())>0)
     throw new IllegalStateException("foreign/future/duplicate cold ledger head");
    revisions.put(state.key(),state.header().revision());
   }
   var state=states.get(new AggregateKey(AggregateKind.WORLD,session.worldId().value()));
   if(!(state instanceof WorldAggregate actualWorld))return Optional.empty(); // Early WORLD bootstrap has not ACKed yet.
   if(!actualWorld.value().worldId().equals(session.worldId())||
     !actualWorld.value().storeGeneration().equals(session.storeGeneration()))
    throw new IllegalStateException("cold WORLD generation mismatch");
   if(!actualWorld.value().bootEpoch().equals(session.bootEpoch()))return Optional.empty();
   var regions=new HashMap<RegionId,RegionRecord>();var cooldowns=new ArrayList<CooldownRecord>();var unique=new ArrayList<UniqueUseRecord>();
   for(var row:states.values()){
    if(row instanceof RegionAggregate region)regions.put(region.value().regionId(),region.value());
    else if(row instanceof CooldownAggregate cooldown)cooldowns.add(cooldown.value());
    else if(row instanceof UniqueAggregate use)unique.add(use.value());
    else if(!(row instanceof WorldAggregate))throw new IllegalStateException("wrong cold metadata aggregate kind");
   }
   var visited=new HashSet<RegionId>();var todo=new ArrayDeque<RegionId>();
   identity.plan().scopes().scopes().stream().filter(s->s.kind()==ScopeKind.REGION).forEach(s->todo.add(new RegionId(s.id())));
   while(!todo.isEmpty()){
    var region=todo.removeFirst();if(!visited.add(region))continue;
    var record=regions.get(region);
    if(record==null)throw new IllegalStateException("sealed real cold region lineage missing");
    for(var parent:record.parentIds()){
     if(!identity.ledgerKeys().contains(new AggregateKey(AggregateKind.REGION,parent.value())))
      throw new IllegalStateException("cold region lineage changed beyond pre-apply keys");
     todo.add(parent);
    }
   }
   var metadata=new CatalogueEventLedger.Metadata(actualWorld.value(),regions,cooldowns,unique);
   if(!identity.ledgerKeys().containsAll(CatalogueEventLedger.keys(identity.selection(),metadata)))
    throw new IllegalStateException("cold original ledger identity changed beyond pre-apply keys");
   if(!session.equals(store.session()))return Optional.empty();
   var scopes=new HashMap<ScopeRef,Revision>();
   scopes.put(new ScopeRef(ScopeKind.WORLD,session.worldId().value()),actualWorld.value().header().revision());
   regions.forEach((id,record)->scopes.put(new ScopeRef(ScopeKind.REGION,id.value()),record.header().revision()));
   return Optional.of(new View(metadata,stamp,revisions,scopes));
  }
 }
 /** Only after the native owner/arrival cleanup gate has independently proved this outcome. */
 public static DirectorFact originalFact(Identity identity,Map<String,JsonValue> actualEvidence){
  var selection=identity.selection();var payload=new HashMap<>(actualEvidence);
  payload.put("definition",new JsonValue.Text(identity.definition().id().value()));
  payload.put("decision",new JsonValue.Text(identity.decision().value().toString()));
  payload.put("source",new JsonValue.Text("CATALOGUE_SELECTED"));
  identity.parentChain().ifPresent(id->payload.put("parent_chain",new JsonValue.Text(id.value().toString())));
  var id=new FactId(UUID.nameUUIDFromBytes(("catalogue-completion-v1\0"+selection.event().value()).getBytes(StandardCharsets.UTF_8)));
  var subjects=identity.plan().scopes().scopes().stream().sorted(Comparator.comparing(ScopeRef::toString)).toList();
  return new DirectorFact(id,identity.definition().completion().factType(),subjects,Optional.of(selection.event()),
    EvidenceStatus.CONFIRMED,payload,true,Optional.empty());
 }
 /** Native proof has already been checked by the caller. Fact, exact bus envelope and
  * ledger rows/blobs belong to the same END transaction with View.scopeRevisions() CAS; Unique/Cooldown keep J04 aggregate revision CAS. */
 public static CatalogueEventLedger.Contribution completed(Identity identity,View view,
   Map<String,JsonValue> actualEvidence,boolean composed,Tick currentDirectorTick){
  var now=clock(view,currentDirectorTick);var fact=originalFact(identity,actualEvidence);
  var ledger=composed?CatalogueEventLedger.completedComposed(identity.selection(),view.metadata(),fact,now):
    CatalogueEventLedger.completed(identity.selection(),view.metadata(),fact,now);
  var records=new ArrayList<>(ledger.records());records.add(new DurableFact(fact));
  records.add(NativeCatalogueFactOutbox.completion(fact,identity.decision(),now));
  return new CatalogueEventLedger.Contribution(records,ledger.blobs());
 }
 public static Tick clock(View view,Tick currentDirectorTick){
  long floor=Math.max(currentDirectorTick.value(),view.metadata().world().simTick().value());
  for(var row:view.metadata().uniqueness())floor=Math.max(floor,row.header().updatedTick().value());
  for(var row:view.metadata().cooldowns())floor=Math.max(floor,row.header().updatedTick().value());
  return new Tick(floor);
 }
}
