package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.WorldEffectPort;
import fr.proutlost.director.api.permission.CommitPermit;
import fr.proutlost.director.bootstrap.DirectorBootstrap.TestOnlyBackend;
import java.util.*;
/** TEST_ONLY fault-injection oracle. No Minecraft access, blocks, entities or physical claims. */
public final class TestWorldEffectPort implements WorldEffectPort,TestOnlyBackend {
 private final CommitPermit.Authority authority;private final LiveValidationSnapshot live;private final SliceResult scriptedResult;private final FaultInjector faults;
 public TestWorldEffectPort(CommitPermit.Authority authority,LiveValidationSnapshot live,SliceResult scriptedResult,FaultInjector faults){this.authority=Objects.requireNonNull(authority);this.live=Objects.requireNonNull(live);this.scriptedResult=Objects.requireNonNull(scriptedResult);this.faults=Objects.requireNonNull(faults);}
 private final List<StabilizationMarker> stopMarkers=new ArrayList<>();
 public List<StabilizationMarker> stopMarkers(){return List.copyOf(stopMarkers);}
 public SliceResult stabilize(PreparedSlice slice,CommitPermit.StabilizationPermit permit,StabilizationLiveSnapshot snapshot){stopMarkers.add(permit.consume(authority,slice,snapshot));faults.at(FaultPoint.FIRST_SLICE);return scriptedResult;}
 public EffectPreparation prepare(EffectIntent intent){throw new UnsupportedOperationException("TEST_ONLY: supply prepared fixture explicitly");}
 public SliceResult apply(PreparedSlice slice,CommitPermit permit){permit.consume(authority,slice,live);faults.at(FaultPoint.FIRST_SLICE);return scriptedResult;}
 public ReconcileResult reconcile(RecoveryProbe probe){return new ReconcileResult(ReconcileStatus.AMBIGUOUS,probe.loadedProofs(),List.of());}
 public CleanupResult cleanup(CleanupIntent intent,CommitPermit permit){throw new UnsupportedOperationException("TEST_ONLY: explicit cleanup fixture required");}
}
