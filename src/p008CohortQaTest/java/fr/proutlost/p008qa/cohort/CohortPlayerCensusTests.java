package fr.proutlost.p008qa.cohort;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Pure observations/DTO fixtures only. Never creates Minecraft players. */
public final class CohortPlayerCensusTests {
    private static int checks;
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    private static void refused(Runnable body,String why){boolean no=false;try{body.run();}catch(IllegalArgumentException expected){no=true;}check(no,why);}
    private static UUID id(int index){return new UUID(0,index);}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("new evidence directory required");var root=Path.of(args[0]).toAbsolutePath();Files.createDirectory(root);
        var allowed=new HashSet<UUID>();for(int i=1;i<=25;i++)allowed.add(id(i));
        var empty=CohortPlayerCensus.inspect(allowed,List.of());check(empty.players()==0,"zero actual connections stays zero");
        check(CohortPlayerCensus.count(Set.copyOf(allowed),List.of())==0,"immutable DTO whitelist permits empty actual PlayerList without contains-null probe");
        check(CohortPlayerCensus.digest(Set.of()).length()==64,"empty immutable observation set has explicit digest");
        var rows=new ArrayList<CohortPlayerCensus.Observed>();for(int i=1;i<=25;i++)rows.add(new CohortPlayerCensus.Observed(id(i),false));
        var full=CohortPlayerCensus.inspect(allowed,rows);check(full.players()==25,"25 actual fixture rows count25");
        Collections.reverse(rows);check(CohortPlayerCensus.inspect(allowed,rows).observedUuidSha256().equals(full.observedUuidSha256()),"set fingerprint order independent");
        rows.removeLast();check(!CohortPlayerCensus.inspect(allowed,rows).observedUuidSha256().equals(full.observedUuidSha256()),"departed actual identity changes fingerprint");
        refused(()->CohortPlayerCensus.inspect(allowed,List.of(new CohortPlayerCensus.Observed(id(1),true))),"FakePlayer observation refused");
        refused(()->CohortPlayerCensus.inspect(allowed,List.of(new CohortPlayerCensus.Observed(id(26),false))),"nonwhitelisted actual UUID refused");
        refused(()->CohortPlayerCensus.inspect(allowed,List.of(new CohortPlayerCensus.Observed(id(1),false),new CohortPlayerCensus.Observed(id(1),false))),"duplicate actual UUID refused");
        rows.add(new CohortPlayerCensus.Observed(id(25),false));rows.add(new CohortPlayerCensus.Observed(id(26),false));refused(()->CohortPlayerCensus.inspect(allowed,rows),"26 actual observations refused");
        var clock=new AtomicLong(1_000_000_000L);
        var ticks=new CompletedTickCapture(root,"counts",new CompletedTickCapture.Limits(30,100,131072,8),Map.of("scope","PURE_COHORT_DTO"),clock::get,()->{});
        check(ticks.offer(1,clock.incrementAndGet(),1,0)==CompletedTickCapture.Offer.QUEUED,"zero player native observation can be recorded");
        check(ticks.offer(2,clock.incrementAndGet(),1,25)==CompletedTickCapture.Offer.QUEUED,"25 player observation can be recorded");
        check(ticks.offer(3,clock.incrementAndGet(),1,26)==CompletedTickCapture.Offer.INVALID,"26 player observation refused rather than truncated");
        ticks.stop(CompletedTickCapture.End.EXPLICIT_STOP);var end=Files.readString(ticks.completion().get(5,TimeUnit.SECONDS));check(end.contains("\"schema_version\":2")&&end.contains("\"end_incomplete\":true"),"cohort schema2 and invalid observation incomplete");
        var csv=Files.readAllLines(ticks.root().resolve("ticks.csv"));check(csv.get(1).split(",",-1)[3].equals("0")&&csv.get(2).split(",",-1)[3].equals("25"),"actual observed counts preserved exactly");
        refused(()->new ActorFrameCapture.Sample(1,1,1,26,1,100,50,10,1,1,1,1,3,-1,0,0,0,0,0,1,3,-1,100,false,"RUNNING","NORMAL",1),"actor bound26 refused");
        System.out.println("CohortPlayerCensusTests checks="+checks+" PASS scope=PURE_NO_PLAYER_CREATION_NO_NATIVE_MEMBERSHIP_ATTESTATION");
    }
}
