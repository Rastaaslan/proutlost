package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed, new TEST_ONLY fixture. No canonical content is replaced or inferred. */
public final class DirectorPublicGroupQaCatalog {
 private DirectorPublicGroupQaCatalog(){}
 public static final DefinitionId EVENT=new DefinitionId("proutlost_director:qa_public_group/observed");
 public static final FactType FACT=new FactType("proutlost_director:qa_public_group_observed");
 public static final ResourceId RECIPE=new ResourceId("proutlost:test_camp_plants");
 public static CatalogSnapshot snapshot(){
  var primitive=new PrimitiveId("proutlost:camp_growth");var cap=new CapabilityId("mutation.plants_v1");
  var descriptor=new PrimitiveDescriptor(primitive,1,new ResourceId("proutlost:codec_camp_growth"),Set.of(cap),BudgetMath.zero(),new ResourceId("proutlost:footprint"),
   Set.of(new ResourceId("proutlost:block_codec")),Set.of(OwnershipDomain.BLOCK),Set.of(),new DurationTicks(6000),PausePolicy.CLEANUP,RecoveryMode.RECOVER,new ResourceId("proutlost:cleanup"),Optional.empty(),Optional.empty(),ProviderMode.TEST_ONLY);
  var step=new EventStep("growth",List.of(),new DurationTicks(0),primitive,new CampGrowth(new TagId("proutlost:abandoned_camp"),RECIPE,1),OnFailure.ABORT_SAFE);
  var event=new EventDefinition(1,new Revision(1),EVENT,Calibration.DEFAULT_FOR_TESTING,Category.HYBRID,Set.of(Family.MEMORY),true,
   new Selection(1,0),new Target(TargetScope.REGION,Set.of(),PlayerPolicy.FAIR,Map.of("observer",new Role(0,0,false))),new RegionTag(new TagId("proutlost:qa_public_group_site")),
   new Timing(new DurationTicks(1200),new Cooldowns(new DurationTicks(0),new DurationTicks(0),new DurationTicks(0))),
   new Uniqueness(UniquenessScope.REPEATABLE,ConsumeOn.EFFECT_CONFIRMED),new BudgetVector(.25,.25,2,0,0,1,0,0,0,0),
   new Conflicts(Set.of("BLOCKS:FOOTPRINT"),Set.of(),Set.of(),Priority.MICRO,Preemption.YIELD_BEFORE_COMMIT),
   new Safety(Risk.SAFE,Set.of(cap),Optional.of(RECIPE),Optional.empty(),PlayerEditPolicy.YIELD,JournalPolicy.PLAYER_OPT_IN,ParticipantLoss.ABORT_IF_MIN_LOST),List.of(step),
   new RecoveryPolicy(RecoveryMode.RECOVER,new DurationTicks(2400)),new Completion(FACT,Optional.empty(),new UnitValue(1)));
  String body="TEST_ONLY_PUBLIC_GROUP_V1\n"+new String(new ModelCodec<>(EventDefinition.class).encode(event),StandardCharsets.UTF_8)+"\n"+new String(new ModelCodec<>(PrimitiveDescriptor.class).encode(descriptor),StandardCharsets.UTF_8);
  return new CatalogSnapshot(new Epoch(1),BlobContent.hash(body.getBytes(StandardCharsets.UTF_8)),Map.of(EVENT,event),Map.of(),Map.of(primitive,descriptor));
 }
}
