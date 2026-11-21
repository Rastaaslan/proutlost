package fr.proutlost.integration.contra;

import fr.proutlost.contract.director.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.DefinitionRef;
import fr.proutlost.director.api.model.EffectIntent;
import fr.proutlost.director.api.port.PhysicalSiteResolverPort;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import static fr.proutlost.contract.director.Vocabulary.Channel;
import static fr.proutlost.contract.director.Vocabulary.SourceType;

/**
 * Full D0 consumer for the existing Director planner. It owns no store or world
 * writer. The installed owner supplies actual observations and policy checks;
 * missing observations/policy adapters refuse selection and forward execution.
 * Requirements are exact definition-hash bindings, not guesses from G0 tags.
 */
public final class D0PreparedSiteResolver implements PhysicalSiteResolverPort {
    public enum PolicyDomain { ACCESS,PRESENTATION,PLAYER_ACTION }
    /** Complete installed adapter classification; no primitive-name heuristics. */
    public record EffectUse(Set<Channel> channels,Set<Id> observableActions,boolean physical) {
        public EffectUse {channels=Set.copyOf(channels);observableActions=Set.copyOf(observableActions);if(channels.isEmpty()&&!physical)throw new IllegalArgumentException("actual presentation channels required");}
    }
    /** The actual policy implementation must consult current authoritative world
     * protection where the policy requires it. A declaration/receipt is not that observation. */
    public interface PolicyAuthority {
        boolean allowed(Id policy,PolicyDomain domain,EventNeed need,
            StaticLocationCatalogEntry entry,EffectIntent effect,EffectUse use);
    }
    /** Immutable descriptors only. Safe on the phase-one compiler worker. */
    public record PolicySupport(Map<PolicyDomain,Set<Id>> known) {
        public PolicySupport {
            var copy=new EnumMap<PolicyDomain,Set<Id>>(PolicyDomain.class);
            known.forEach((domain,ids)->copy.put(Objects.requireNonNull(domain),Set.copyOf(ids)));
            known=Map.copyOf(copy);
        }
        public boolean supports(Id policy,PolicyDomain domain){return known.getOrDefault(domain,Set.of()).contains(policy);}
    }
    /** Current recorded bindings, not an empty-default replacement for an unknown binding. */
    @FunctionalInterface public interface ExistingBindings {Optional<LocationRef> lookup(Id binding);}
    private record Captured(List<LocationRef> refs,Map<LocationRef,fr.proutlost.contract.director.Revision> protections,
            PhysicalCatalogModels.Key sourceKey,PhysicalCatalogModels.Binding binding,
            fr.proutlost.contract.director.CatalogSnapshot source,EventNeed need) {
        Captured {refs=List.copyOf(refs);protections=Map.copyOf(protections);}
    }
    private final DurablePhysicalCatalogView view;
    private final Map<DefinitionRef,EventNeed> requirements;
    private final Map<PrimitiveId,EffectUse> effects;
    private final Map<SourceType,Id> sourceTypes;
    private final ObservationPort observations;
    private final PolicyAuthority policies;
    private final PolicySupport policySupport;
    private final ExistingBindings bindings;
    private final Supplier<Instant> clock;
    private final Runnable actor;
    private final Map<BlobRef,Captured> captured=new LinkedHashMap<>();
    private final Map<BlobRef,PhysicalSiteSelection> previews=new LinkedHashMap<>();

    public D0PreparedSiteResolver(DurablePhysicalCatalogView view,Map<DefinitionRef,EventNeed> requirements,
            Map<PrimitiveId,EffectUse> effects,Map<SourceType,Id> sourceTypes,
            ObservationPort observations,PolicySupport policySupport,PolicyAuthority policies,ExistingBindings bindings,
            Supplier<Instant> clock,Runnable actor){
        this.view=Objects.requireNonNull(view);this.requirements=Map.copyOf(requirements);
        this.effects=Map.copyOf(effects);this.sourceTypes=Map.copyOf(sourceTypes);
        this.observations=Objects.requireNonNull(observations);this.policies=Objects.requireNonNull(policies);
        this.policySupport=Objects.requireNonNull(policySupport);
        this.bindings=Objects.requireNonNull(bindings);this.clock=Objects.requireNonNull(clock);this.actor=Objects.requireNonNull(actor);
        if(this.requirements.size()>1024||this.effects.size()>256)throw new IllegalArgumentException("physical resolver capacity");
        for(var row:this.requirements.entrySet()){
            Objects.requireNonNull(row.getKey());Objects.requireNonNull(row.getValue());
            require(row.getValue().evidence().size()>0,"authored need evidence required");
        }
    }
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
    private void actor(){actor.run();}
    @Override public boolean requires(DefinitionRef definition){actor();return requirements.containsKey(definition);}

    /** Can be used by the phase-one compiler: actual installed adapters, never generic 'all supported'. */
    public void requireSupported(fr.proutlost.contract.director.CatalogSnapshot snapshot){
        for(var entry:snapshot.entries()){
            require(sourceTypes.containsKey(entry.source_type()),"unknown physical source type adapter");
            for(var capability:entry.capabilities()){
                CapabilityTypes.requireKnown(capability.capability_id());
                require(capability.capability_version().value().equals("1"),"unknown physical capability version");
            }
            for(var zone:entry.protection()){
                require(policySupport.supports(zone.access_policy(),PolicyDomain.ACCESS),"unknown access policy");
                require(policySupport.supports(zone.presentation_policy(),PolicyDomain.PRESENTATION),"unknown presentation policy");
                require(policySupport.supports(zone.player_action_policy(),PolicyDomain.PLAYER_ACTION),"unknown player action policy");
            }
            for(var surface:entry.surfaces())
                require(policySupport.supports(surface.player_action_policy(),PolicyDomain.PLAYER_ACTION),"unknown surface action policy");
        }
    }

    @Override public Optional<PhysicalSiteSelection> resolve(Candidate candidate,DecisionInput input,
            EventInstanceId event,List<PreparedEffect> actualPreview){
        actor();Objects.requireNonNull(candidate);Objects.requireNonNull(input);Objects.requireNonNull(event);
        var need=requirements.get(candidate.definition());
        if(need==null)return Optional.empty(); // Caller distinguishes this with requires().
        require(need.resources().size()<=256&&need.preferences().size()<=256,"bounded authored D0 resources/preferences");
        var actualDefinition=Objects.requireNonNull(input.catalog().events().get(candidate.definition().id()),"actual definition absent");
        require(new DefinitionRef(actualDefinition.id(),actualDefinition.contentRevision(),BlobContent.hash(
            new ModelCodec<>(fr.proutlost.director.api.model.EventDefinition.class).encode(actualDefinition))).equals(candidate.definition()),"authored need definition hash differs from actual catalogue");
        require(actualDefinition.category().name().equals(need.family().name()),"D0 family differs from actual definition category");
        var source=view.snapshot();var readback=view.readback().orElseThrow(()->new IllegalStateException("D0 publication unavailable"));
        var binding=view.binding().orElseThrow(()->new IllegalStateException("D0 explicit binding unavailable"));
        require(need.world_id().equals(source.world_id()),"need/source world differs");
        require(input.state().world().worldId().equals(binding.directorWorld()),"need/Director world differs");
        requireSupported(source);
        List<EffectIntent> intents=actualPreview.stream().map(PreparedEffect::intent).toList();
        require(!intents.isEmpty()&&intents.size()<=256,"actual complete effect preview required");
        Map<OperationToken,Footprint> footprints=new LinkedHashMap<>();
        for(var intent:intents){
            require(intent.instanceId().equals(event),"preview event differs");
            require(footprints.putIfAbsent(intent.operationToken(),intent.expectedFootprint())==null,"duplicate preview operation");
            require(effects.containsKey(intent.primitive()),"unknown required effect semantic adapter");
        }
        var requested=new ArrayList<LocationRef>();
        if(!need.required_locations().isEmpty())requested.addAll(need.required_locations());
        if(need.same_as_binding()!=null){
            var same=bindings.lookup(need.same_as_binding());if(same.isEmpty())return Optional.empty();
            if(!requested.isEmpty()&&!requested.contains(same.orElseThrow()))return Optional.empty();
            if(requested.isEmpty())requested.add(same.orElseThrow());
        }
        if(!requested.isEmpty())return select(candidate,event,source,readback,binding,need,requested,intents,footprints);
        // Query only current prepared entries, not historical lookup results. Walk all pages;
        // capacity refusal is explicit, never a successful truncated candidate set.
        var options=new ArrayList<LocationRef>();Id after=null;
        var required=need.required_capabilities().stream().map(CapabilityRequirement::id).collect(Collectors.toSet());
        while(true){
            var page=view.query(new CatalogQuery(required,need.semantic_region_ids(),after,128));
            if(page.isEmpty())break;
            options.addAll(page);require(options.size()<=1024,"D0 selection query capacity");
            Id next=page.getLast().location_id();require(after==null||next.compareTo(after)>0,"D0 query did not advance");
            after=next;if(page.size()<128)break;
        }
        var priorities=source.regions().stream().collect(Collectors.toMap(SemanticRegion::region_id,SemanticRegion::priority));
        options.sort(Comparator.<LocationRef>comparingLong(ref->preference(need,view.lookup(ref).orElseThrow(),priorities,input)).reversed()
            .thenComparing(ref->tie(need,ref,input.seed())).thenComparing(LocationRef::location_id));
        for(var ref:options){
            var selected=select(candidate,event,source,readback,binding,need,List.of(ref),intents,footprints);
            if(selected.isPresent())return selected;
        }
        return Optional.empty();
    }

    private static String tie(EventNeed need,LocationRef ref,long seed){
        var value=new JsonValue.Array(List.of(text(need.world_id().value()),text(need.need_id().value()),text(Long.toString(seed)),text(ref.location_id().value()),text(ref.revision().value())));
        return BlobContent.hash(CanonicalJsonV1.encode(value)).value();
    }
    private static long preference(EventNeed need,StaticLocationCatalogEntry entry,Map<SemanticRegionId,Integer> priorities,DecisionInput input){
        if(need.preferences().isEmpty())return entry.semantic_region_ids().stream().mapToInt(r->priorities.getOrDefault(r,Integer.MIN_VALUE)).max().orElse(Integer.MIN_VALUE);
        long score=0;for(var p:need.preferences()){
            long normalized;
            if(p.criterion().value().equals("proutlost:tag")){
                require(p.parameters().type_id().value().equals("proutlost:id")&&p.parameters().fields().get("value") instanceof TypedValue.Identifier,"typed tag preference required");
                var id=((TypedValue.Identifier)p.parameters().fields().get("value")).value();normalized=entry.tags().contains(id)?1_000_000:0;
            }else{
                require(p.parameters().type_id().value().equals("proutlost:range")&&p.parameters().fields().get("min") instanceof TypedValue.Number&&p.parameters().fields().get("max") instanceof TypedValue.Number,"typed bounded preference normalization required");
                long min=((TypedValue.Number)p.parameters().fields().get("min")).value(),max=((TypedValue.Number)p.parameters().fields().get("max")).value();require(max>min,"preference range");double actual;
                if(p.criterion().value().equals("proutlost:semantic_priority"))actual=entry.semantic_region_ids().stream().mapToInt(r->priorities.getOrDefault(r,Integer.MIN_VALUE)).max().orElse(Integer.MIN_VALUE);
                else if(p.criterion().value().equals("proutlost:distance")){
                    var witnesses=input.context().context().players().values();require(!witnesses.isEmpty(),"actual witness required for distance preference");
                    actual=Double.POSITIVE_INFINITY;for(var b:entry.geometry())for(var witness:witnesses){var pos=witness.position().position();if(!pos.dimension().value().equals(b.dimension_id().value()))continue;
                        double x=(b.min().x()+(double)b.max().x())/2,z=(b.min().z()+(double)b.max().z())/2;actual=Math.min(actual,Math.hypot(pos.x()-x,pos.z()-z));}
                    require(Double.isFinite(actual),"distance preference dimension unavailable");
                }else throw new IllegalArgumentException("unknown required D0 preference interpreter: "+p.criterion());
                normalized=Math.round(Math.clamp((actual-min)/(max-(double)min),0,1)*1_000_000);if(p.criterion().value().equals("proutlost:distance"))normalized=1_000_000-normalized;
            }
            score=Math.addExact(score,Math.multiplyExact(normalized,(long)p.weight()));
        }return score;
    }

    private Optional<PhysicalSiteSelection> select(Candidate candidate,EventInstanceId event,
            fr.proutlost.contract.director.CatalogSnapshot source,PhysicalCatalogModels.Readback readback,
            PhysicalCatalogModels.Binding binding,EventNeed need,List<LocationRef> refs,
            List<EffectIntent> intents,Map<OperationToken,Footprint> footprints){
        try{
            require(new HashSet<>(refs).size()==refs.size()&&!refs.isEmpty()&&refs.size()<=128,"bounded distinct physical locations required");
            var geometry=refs.stream().map(view::lookup).map(Optional::orElseThrow).flatMap(e->e.geometry().stream()).toList();
            if(intents.stream().anyMatch(i->!contained(geometry,i.expectedFootprint())))return Optional.empty();
            var entries=new ArrayList<StaticLocationCatalogEntry>();
            var observed=new LinkedHashMap<LocationRef,LocationObservation>();
            for(var ref:refs){
                require(view.current(ref),"historical location not newly eligible");
                var entry=view.lookup(ref).orElseThrow();
                validateEntry(need,entry,intents);entries.add(entry);observed.put(ref,observe(need,entry));
            }
            validateSet(need,entries,refs,intents);
            var needBlob=BlobContent.capture(PhysicalSitePin.NEED_CODEC,1,StrictCodec.encode(need).getBytes(StandardCharsets.UTF_8));
            var resources=D0LogicalResources.seal(need,entries,readback.key());
            var root=new TreeMap<String,JsonValue>();
            root.put("resources",new JsonValue.Array(resources.stream().map(r->decode(new ModelCodec<>(PhysicalResourceClaim.class).encode(r))).toList()));
            root.put("schema_version",number(1));root.put("need_sha256",text(needBlob.reference().hash().value()));
            root.put("candidate",text(candidate.id().value().toString()));root.put("event",text(event.value().toString()));
            root.put("definition",decode(new ModelCodec<>(DefinitionRef.class).encode(candidate.definition())));
            root.put("source_key",decode(new ModelCodec<>(PhysicalCatalogModels.Key.class).encode(readback.key())));
            root.put("entries",new JsonValue.Array(entries.stream().map(e->decode(StrictCodec.encode(e).getBytes(StandardCharsets.UTF_8))).toList()));
            root.put("observations",new JsonValue.Array(observed.values().stream().map(o->decode(StrictCodec.encode(o).getBytes(StandardCharsets.UTF_8))).toList()));
            var selected=BlobContent.capture(PhysicalSitePin.SELECTION_CODEC,1,CanonicalJsonV1.encode(new JsonValue.ObjectValue(root)));
            Set<LocationId> locations=new HashSet<>();Set<AnchorId> anchors=new HashSet<>();Set<RegionId> regions=new HashSet<>();
            for(var entry:entries){
                var id=new ResourceId(entry.location_id().value());locations.add(Objects.requireNonNull(binding.locations().get(id),"explicit location binding missing"));
                for(var r:entry.semantic_region_ids())regions.add(Objects.requireNonNull(binding.regions().get(new ResourceId(r.value().value())),"explicit region binding missing"));
                for(var a:entry.anchors()){
                    var match=binding.anchors().stream().filter(b->b.sourceLocation().equals(id)&&b.sourceAnchor().value().equals(a.anchor_id().value())).toList();
                    require(match.size()==1,"explicit local anchor binding absent/ambiguous");anchors.add(match.getFirst().directorAnchor());
                }
            }
            var selection=new PhysicalSiteSelection(readback,candidate.definition(),candidate.id(),event,needBlob,selected,locations,anchors,regions,footprints,resources);
            var protections=observed.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue().protection_revision()));
            require(captured.size()<128||captured.containsKey(selected.reference()),"physical preview capacity");
            captured.put(selected.reference(),new Captured(refs,protections,readback.key(),binding,source,need));previews.put(selected.reference(),selection);
            return Optional.of(selection);
        }catch(IllegalArgumentException|NoSuchElementException refused){return Optional.empty();}
    }

    private void validateEntry(EventNeed need,StaticLocationCatalogEntry entry,List<EffectIntent> intents){
        require(need.world_id().equals(entry.world_id()),"physical need world changed");
        if(!need.source_types().isEmpty())require(need.source_types().contains(sourceTypes.get(entry.source_type())),"physical source type mismatch");
        if(!need.semantic_region_ids().isEmpty())require(!Collections.disjoint(need.semantic_region_ids(),entry.semantic_region_ids()),"physical semantic region mismatch");
        if(!need.dimensions().isEmpty())require(entry.geometry().stream().allMatch(b->need.dimensions().contains(b.dimension_id())),"physical dimension mismatch");
        require(entry.presentation_channels().containsAll(need.channels()),"required presentation channel missing");
        for(var intent:intents){
            var use=Objects.requireNonNull(effects.get(intent.primitive()),"effect adapter unknown");
            if(!intersects(entry.geometry(),intent.expectedFootprint()))continue;
            require(need.channels().containsAll(use.channels())&&entry.presentation_channels().containsAll(use.channels()),"actual effect channel not admitted");
            for(var zone:entry.protection()){
                if(!intersects(List.of(zone.bounds()),intent.expectedFootprint()))continue;
                policy(zone.access_policy(),PolicyDomain.ACCESS,need,entry,intent,use);
                policy(zone.presentation_policy(),PolicyDomain.PRESENTATION,need,entry,intent,use);
                policy(zone.player_action_policy(),PolicyDomain.PLAYER_ACTION,need,entry,intent,use);
            }
            for(var surface:entry.surfaces()){
                boolean relevant=!Collections.disjoint(surface.observable_actions(),use.observableActions())
                    ||need.required_surface_kinds().contains(surface.kind())
                    ||entry.capabilities().stream().filter(c->need.required_capabilities().stream().anyMatch(r->r.id().equals(c.capability_id())))
                        .anyMatch(c->c.surface_ids().contains(surface.surface_id()));
                if(relevant)
                    policy(surface.player_action_policy(),PolicyDomain.PLAYER_ACTION,need,entry,intent,use);
            }
        }
    }
    private void policy(Id policy,PolicyDomain domain,EventNeed need,StaticLocationCatalogEntry entry,EffectIntent intent,EffectUse use){
        require(policySupport.supports(policy,domain),"unknown required "+domain+" policy");
        require(policies.allowed(policy,domain,need,entry,intent,use),"physical "+domain+" policy refused");
    }
    private void validateSet(EventNeed need,List<StaticLocationCatalogEntry> entries,List<LocationRef> refs,List<EffectIntent> intents){
        for(var id:need.distinct_from_bindings()){
            var bound=bindings.lookup(id);require(bound.isPresent(),"required distinct binding unknown");
            require(!refs.contains(bound.orElseThrow()),"distinct physical site required");
        }
        Set<Id> records=new HashSet<>(),surfaceKinds=new HashSet<>();
        for(var entry:entries)for(var surface:entry.surfaces()){records.addAll(surface.record_ids());surfaceKinds.add(surface.kind());}
        require(records.containsAll(need.required_records()),"required physical record missing");
        require(surfaceKinds.containsAll(need.required_surface_kinds()),"required surface kind missing");
        Set<Id> supportedActions=new HashSet<>();
        for(var entry:entries){
            var selectedSurfaceIds=entry.capabilities().stream()
                .filter(c->need.required_capabilities().stream().anyMatch(r->r.id().equals(c.capability_id())))
                .flatMap(c->c.surface_ids().stream()).collect(Collectors.toSet());
            entry.surfaces().stream().filter(s->selectedSurfaceIds.contains(s.surface_id())||need.required_surface_kinds().contains(s.kind()))
                .forEach(s->supportedActions.addAll(s.observable_actions()));
        }
        for(var intent:intents)require(supportedActions.containsAll(effects.get(intent.primitive()).observableActions()),"actual action is not exposed by a selected capability/surface");
        for(var required:need.required_capabilities()){
            boolean matched=false;
            for(var entry:entries)for(var cap:entry.capabilities())if(cap.capability_id().equals(required.id())){
                require(cap.capability_version().equals(cap.parameters().version()),"capability version changed");
                if(!required.predicates().stream().allMatch(p->matches(cap.parameters(),p)))continue;
                if(cap.parameters().fields().get("capacity") instanceof TypedValue.Number n&&n.value()<need.min_capacity())continue;
                long capacity=entry.anchors().stream().filter(a->cap.anchor_ids().contains(a.anchor_id())).mapToLong(Anchor::capacity).sum();
                if(capacity<need.min_capacity())continue;
                matched=true;
            }
            require(matched,"required capability/predicate/capacity unavailable");
        }
        for(var intent:intents)require(contained(entries.stream().flatMap(e->e.geometry().stream()).toList(),intent.expectedFootprint()),"effect footprint outside complete physical geometry");
        for(var fallback:need.recovery().fallback_refs()){
            var entry=view.lookup(fallback).orElseThrow(()->new IllegalArgumentException("recovery fallback absent"));
            observe(need,entry,false); // Fallback evidence is independent of the original scene's capabilities.
        }
    }
    private LocationObservation observe(EventNeed need,StaticLocationCatalogEntry entry){
        return observe(need,entry,true);
    }
    private LocationObservation observe(EventNeed need,StaticLocationCatalogEntry entry,boolean originalRequirements){
        var ref=ref(entry);var observation=observations.current(need.world_id(),ref).orElseThrow(()->new IllegalArgumentException("physical observation unknown"));
        var now=Objects.requireNonNull(clock.get());
        require(observation.world_id().equals(need.world_id())&&observation.location().equals(ref)
            &&observation.loaded()&&observation.observable()&&!observation.observed_at().isAfter(now)
            &&observation.expires_at().isAfter(now)&&!observation.evidence().isEmpty()&&observation.conflicts().isEmpty(),"physical observation stale/unloaded/protected");
        if(originalRequirements)require(observation.usable_capabilities().containsAll(need.required_capabilities().stream().map(CapabilityRequirement::id).collect(Collectors.toSet())),"physical capability not currently usable");
        for(var request:originalRequirements?need.resources():List.<ResourceRequest>of()){
            require(!observation.unavailable_resources().contains(request.key().scope_id())
                &&(request.key().sub_id()==null||!observation.unavailable_resources().contains(request.key().sub_id())),"required physical resource unavailable");
        }
        return observation;
    }

    /** Closed predicate evaluation; unsupported field/type combinations are false, never ignored. */
    public static boolean matches(TypedValue actual,fr.proutlost.contract.director.Predicate predicate){
        String field=predicate.field().value();int colon=field.indexOf(':');
        if(colon<0||!field.substring(0,colon).equals("proutlost"))return false;
        var value=actual.fields().get(field.substring(colon+1));if(value==null)return false;
        var expected=predicate.value();
        var target=expected.fields().containsKey("value")?expected.fields().get("value"):expected.fields().get(field.substring(colon+1));
        return switch(predicate.op()){
            case EQ -> Objects.equals(value,target);
            case RANGE -> value instanceof TypedValue.Number n&&expected.fields().get("min") instanceof TypedValue.Number min
                &&expected.fields().get("max") instanceof TypedValue.Number max&&n.value()>=min.value()&&n.value()<=max.value();
            case IN -> target instanceof TypedValue.Identifiers ids&&value instanceof TypedValue.Identifier id&&ids.value().contains(id.value())
                ||target instanceof TypedValue.Names names&&value instanceof TypedValue.Text text&&names.value().contains(text.value());
            case CONTAINS_ALL -> target instanceof TypedValue.Identifiers ids&&value instanceof TypedValue.Identifiers actualIds&&new HashSet<>(actualIds.value()).containsAll(ids.value())
                ||target instanceof TypedValue.Names names&&value instanceof TypedValue.Names actualNames&&new HashSet<>(actualNames.value()).containsAll(names.value());
        };
    }

    @Override public PhysicalSiteOffer pin(PhysicalSiteSelection selection,ExecutionPlan plan){
        actor();require(previews.get(selection.selection().reference())==selection,"uncaptured physical selection");
        var offer=selection.pin(plan);requireCurrent(offer.pin(),plan,Optional.empty());return offer;
    }
    @Override public void discard(PhysicalSiteSelection unselected){actor();captured.remove(unselected.selection().reference());previews.remove(unselected.selection().reference());}
    @Override public void restore(PhysicalSiteOffer offer){
        actor();var pin=offer.pin();
        BlobContent needBlob=offer.blobs().stream().filter(b->b.reference().equals(pin.need())).findFirst().orElseThrow();
        var need=StrictCodec.decode(new String(needBlob.bytes(),StandardCharsets.UTF_8),EventNeed.class);
        require(Arrays.equals(needBlob.bytes(),StrictCodec.encode(need).getBytes(StandardCharsets.UTF_8))
            &&need.world_id().value().equals(pin.catalog().key().sourceWorld().value()),"historical J04 need bytes/world differ");
        BlobContent selection=offer.blobs().stream().filter(b->b.reference().equals(pin.selection())).findFirst().orElseThrow();
        var root=((JsonValue.ObjectValue)CanonicalJsonV1.decode(selection.bytes())).values();
        require(root.keySet().equals(Set.of("schema_version","need_sha256","candidate","event","definition","source_key","entries","observations","resources")),"restored selection fields");
        require(root.get("schema_version").equals(number(1))&&root.get("need_sha256").equals(text(pin.need().hash().value()))
            &&root.get("candidate").equals(text(pin.candidate().value().toString()))&&root.get("event").equals(text(pin.event().value().toString())),"restored selection identity");
        var definition=new ModelCodec<>(DefinitionRef.class).decode(CanonicalJsonV1.encode(root.get("definition")));
        var key=new ModelCodec<>(PhysicalCatalogModels.Key.class).decode(CanonicalJsonV1.encode(root.get("source_key")));
        require(definition.equals(pin.definition())&&key.equals(pin.catalog().key()),"restored source/definition differs");
        var publication=view.publication(pin.catalog().key()).orElseThrow(()->new IllegalArgumentException("historical source not actually J04 recovered"));
        var source=StrictCodec.decode(new String(publication.source(),StandardCharsets.UTF_8),fr.proutlost.contract.director.CatalogSnapshot.class);
        CatalogValidation.requireSealed(source);var binding=publication.binding();
        require(stableReadback(publication.readback(),pin.catalog()),"restored source not actually recovered");
        var entries=((JsonValue.Array)root.get("entries")).values().stream().map(v->StrictCodec.decode(new String(CanonicalJsonV1.encode(v),StandardCharsets.UTF_8),StaticLocationCatalogEntry.class)).toList();
        var observations=((JsonValue.Array)root.get("observations")).values().stream().map(v->StrictCodec.decode(new String(CanonicalJsonV1.encode(v),StandardCharsets.UTF_8),LocationObservation.class)).toList();
        require(!entries.isEmpty()&&entries.size()<=128&&observations.size()==entries.size(),"restored physical selection capacity");
        var resources=((JsonValue.Array)root.get("resources")).values().stream().map(v->new ModelCodec<>(PhysicalResourceClaim.class).decode(CanonicalJsonV1.encode(v))).toList();
        require(resources.equals(pin.resources())&&resources.equals(D0LogicalResources.seal(need,entries,pin.catalog().key())),"historical exact logical resources/capacities differ");
        List<LocationRef> refs=entries.stream().map(D0PreparedSiteResolver::ref).toList();
        require(new HashSet<>(refs).size()==refs.size(),"restored duplicate physical locations");
        Set<LocationId> mappedLocations=new HashSet<>();Set<RegionId> mappedRegions=new HashSet<>();Set<AnchorId> mappedAnchors=new HashSet<>();
        for(var entry:entries){
            var id=new ResourceId(entry.location_id().value());mappedLocations.add(Objects.requireNonNull(binding.locations().get(id),"historical location binding absent"));
            for(var r:entry.semantic_region_ids())mappedRegions.add(Objects.requireNonNull(binding.regions().get(new ResourceId(r.value().value())),"historical region binding absent"));
            for(var anchor:entry.anchors()){
                var matches=binding.anchors().stream().filter(a->a.sourceLocation().equals(id)&&a.sourceAnchor().value().equals(anchor.anchor_id().value())).toList();
                require(matches.size()==1,"historical local anchor binding absent/ambiguous");mappedAnchors.add(matches.getFirst().directorAnchor());
            }
        }
        require(pin.locations().equals(mappedLocations)&&pin.regions().equals(mappedRegions)&&pin.anchors().equals(mappedAnchors),"historical explicit identity mapping differs from pinned plan");
        Map<LocationRef,fr.proutlost.contract.director.Revision> protections=new HashMap<>();
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);require(view.lookup(ref(entry)).filter(entry::equals).isPresent(),"restored physical descriptor changed");
            var observation=observations.get(i);require(observation.location().equals(ref(entry))&&observation.world_id().equals(need.world_id()),"restored observation pin changed");
            protections.put(ref(entry),observation.protection_revision());
        }
        require(captured.size()<128||captured.containsKey(pin.selection()),"restored physical selection capacity");
        captured.put(pin.selection(),new Captured(refs,protections,key,binding,source,need));
    }
    @Override public void requireCurrent(PhysicalSitePin pin,ExecutionPlan plan,Optional<EffectIntent> effect){
        actor();pin.requirePlan(plan);var state=Objects.requireNonNull(captured.get(pin.selection()),"physical pin not captured/restored from actual J04");
        require(Objects.equals(requirements.get(pin.definition()),state.need()),"authored physical need changed");
        var current=view.readback().orElseThrow(()->new IllegalArgumentException("physical publication unavailable"));
        require(stableReadback(current,pin.catalog())&&current.key().equals(state.sourceKey())
            &&view.binding().filter(state.binding()::equals).isPresent(),"physical source/binding/session generation changed");
        var intents=effect.map(List::of).orElseGet(plan::effects);
        if(effect.isPresent())require(plan.effects().contains(effect.orElseThrow()),"foreign physical step");
        var entries=new ArrayList<StaticLocationCatalogEntry>();
        for(var ref:state.refs()){
            require(view.current(ref),"physical location retired since selection");
            var entry=view.lookup(ref).orElseThrow();
            var original=state.source().entries().stream().filter(e->ref(e).equals(ref)).findFirst().orElseThrow();
            require(entry.equals(original),"physical descriptor changed");
            var observation=observe(state.need(),entry);
            require(observation.protection_revision().equals(state.protections().get(ref)),"physical protection revision changed");
            validateEntry(state.need(),entry,intents);entries.add(entry);
        }
        validateSet(state.need(),entries,state.refs(),intents);
    }
    private static boolean stableReadback(PhysicalCatalogModels.Readback current,PhysicalCatalogModels.Readback original){
        return current.key().equals(original.key())&&current.completeSource().equals(original.completeSource())
            &&current.projectionDigest().equals(original.projectionDigest())
            &&current.session().worldId().equals(original.session().worldId())
            &&current.session().storeGeneration().equals(original.session().storeGeneration());
    }
    @Override public void retire(PhysicalSitePin pin){actor();captured.remove(pin.selection());previews.remove(pin.selection());}
    private static LocationRef ref(StaticLocationCatalogEntry e){return new LocationRef(fr.proutlost.contract.director.Vocabulary.LocationStore.CATALOG,e.location_id(),e.revision());}
    private static JsonValue decode(byte[] bytes){return CanonicalJsonV1.decode(bytes);}
    private static JsonValue.Text text(String value){return new JsonValue.Text(value);}
    private static JsonValue.Number number(long value){return new JsonValue.Number(java.math.BigDecimal.valueOf(value));}
    private static boolean point(Bounds b,BlockPosition p){return b.dimension_id().value().equals(p.dimension().value())
        &&p.x()>=b.min().x()&&p.x()<=b.max().x()&&p.y()>=b.min().y()&&p.y()<=b.max().y()&&p.z()>=b.min().z()&&p.z()<=b.max().z();}
    private static boolean box(Bounds b,Aabb a){return b.dimension_id().value().equals(a.dimension().value())
        &&a.minX()>=b.min().x()&&a.minY()>=b.min().y()&&a.minZ()>=b.min().z()
        &&a.maxXExclusive()<=((long)b.max().x()+1)&&a.maxYExclusive()<=((long)b.max().y()+1)&&a.maxZExclusive()<=((long)b.max().z()+1);}
    static boolean contained(List<Bounds> geometry,Footprint f){
        if(f.cells().isEmpty()&&f.bounds().isEmpty())return false; // An unbounded global effect cannot inherit a local site's authority.
        return f.cells().stream().allMatch(p->geometry.stream().anyMatch(b->point(b,p)))
            &&f.bounds().stream().allMatch(a->geometry.stream().anyMatch(b->box(b,a)));
    }
    private static boolean intersects(List<Bounds> geometry,Footprint f){
        return f.cells().stream().anyMatch(p->geometry.stream().anyMatch(b->point(b,p)))||f.bounds().stream().anyMatch(a->geometry.stream().anyMatch(b->
            b.dimension_id().value().equals(a.dimension().value())&&a.maxXExclusive()>b.min().x()&&a.minX()<((long)b.max().x()+1)
                &&a.maxYExclusive()>b.min().y()&&a.minY()<((long)b.max().y()+1)&&a.maxZExclusive()>b.min().z()&&a.minZ()<((long)b.max().z()+1)));
    }
}
