package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import java.util.function.Supplier;
/** Knowledge fences preserve UNKNOWN through negation/composite conditions. */
public final class ChainConditionKnowledge {
 private ChainConditionKnowledge(){}
 public static <T> T evaluate(Condition condition,boolean scarKnown,
      Supplier<T> evaluated,T unknown){
  return !scarKnown&&mentionsScar(condition)?unknown:evaluated.get();
 }
 public static <T> T evaluate(Condition condition,boolean scarKnown,boolean requestsKnown,
      Supplier<T> evaluated,T unknown){
  return !requestsKnown&&mentionsRequest(condition)?unknown:evaluate(condition,scarKnown,evaluated,unknown);
 }
 private static boolean mentionsRequest(Condition condition){
  return switch(condition){
   case RequirementPending pending->true;
   case AllConditions all->all.conditions().stream().anyMatch(ChainConditionKnowledge::mentionsRequest);
   case AnyConditions any->any.conditions().stream().anyMatch(ChainConditionKnowledge::mentionsRequest);
   case NotCondition not->mentionsRequest(not.condition());
   default->false;
  };
 }
 public static boolean mentionsScar(Condition condition){
  return switch(condition){
   case MemoryFlag flag->flag.flag().value().equals("proutlost:scar_still_relevant");
   case AllConditions all->all.conditions().stream().anyMatch(ChainConditionKnowledge::mentionsScar);
   case AnyConditions any->any.conditions().stream().anyMatch(ChainConditionKnowledge::mentionsScar);
   case NotCondition not->mentionsScar(not.condition());
   default->false;
  };
 }
}
