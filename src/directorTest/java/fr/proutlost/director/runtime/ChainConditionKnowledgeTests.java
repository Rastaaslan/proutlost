package fr.proutlost.director.runtime;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.engine.ConditionEvaluator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** TEST_ONLY pure knowledge-fence regression, no world/client/ACK assertion. */
public final class ChainConditionKnowledgeTests {
 private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static int run(){
  var scar=new MemoryFlag(new TagId("proutlost:scar_still_relevant"),false);
  var nested=new NotCondition(new AllConditions(List.of(new PlayersPresent(1),
    new AnyConditions(List.of(new NotCondition(scar),new MemoryFlag(new TagId("proutlost:other"),true))))));
  var calls=new AtomicInteger();
  check(ChainConditionKnowledge.evaluate(nested,false,()->{calls.incrementAndGet();return ConditionEvaluator.Truth.TRUE;},ConditionEvaluator.Truth.UNKNOWN)
    ==ConditionEvaluator.Truth.UNKNOWN,"unknown scar nested under NOT/ALL/ANY must not become TRUE");
  check(calls.get()==0,"unknown scar must fence evaluation before absence can be interpreted as false");
  check(ChainConditionKnowledge.evaluate(nested,true,()->{calls.incrementAndGet();return ConditionEvaluator.Truth.FALSE;},ConditionEvaluator.Truth.UNKNOWN)
    ==ConditionEvaluator.Truth.FALSE,"known scar delegates the original exact expression");
  check(calls.get()==1,"known condition evaluated once");
  check(ChainConditionKnowledge.evaluate(new MemoryFlag(new TagId("proutlost:other"),false),false,
    ()->ConditionEvaluator.Truth.TRUE,ConditionEvaluator.Truth.UNKNOWN)==ConditionEvaluator.Truth.TRUE,"unrelated memory flag retains original evaluation");
  var pending=new NotCondition(new AllConditions(List.of(new PlayersPresent(1),
   new RequirementPending(new DefinitionId("test:actual_request_selector")))));
  check(ChainConditionKnowledge.evaluate(pending,true,false,()->ConditionEvaluator.Truth.TRUE,ConditionEvaluator.Truth.UNKNOWN)
    ==ConditionEvaluator.Truth.UNKNOWN,"missing request census stays UNKNOWN beneath NOT and ALL");
  check(ChainConditionKnowledge.evaluate(pending,true,true,()->ConditionEvaluator.Truth.FALSE,ConditionEvaluator.Truth.UNKNOWN)
    ==ConditionEvaluator.Truth.FALSE,"verified request census delegates exact existing evaluator");
  check(ChainConditionKnowledge.evaluate(new PlayersPresent(1),true,false,()->ConditionEvaluator.Truth.TRUE,ConditionEvaluator.Truth.UNKNOWN)
    ==ConditionEvaluator.Truth.TRUE,"request census absence does not block unrelated conditions");
  return 8;
 }
 public static void main(String[] args){System.out.println("PASS ChainConditionKnowledge assertions="+run()+" pure TEST_ONLY");}
}
