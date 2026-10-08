package fr.proutlost.worldprep.validation;

import fr.proutlost.worldprep.environment.EnvironmentalMutation;
import fr.proutlost.worldprep.pipeline.*;
import fr.proutlost.worldprep.storage.PlanManifest;
import fr.proutlost.worldprep.structure.*;
import java.util.*;

/** Deterministic fail-closed validation of exact manifests and cross-pass environmental ownership. */
public final class EnvironmentalValidator {
 public record Context(PipelineRun run,Map<PassId,PlanManifest> manifests,List<EnvironmentalMutation> mutations,StructureReservationMap reservations,Set<EnvironmentalMutation.Position> protectedPositions,Set<String> registeredStates,boolean structureEnvelopeViolation){}
 public ValidationReport validate(Context context){var findings=new ArrayList<ValidationReport.Finding>();
  for(var pass:PassGraph.order())if(pass!=PassId.VALIDATION&&!context.manifests().containsKey(pass))add(findings,ValidationReport.Severity.FATAL,"MISSING_PLAN",pass.name());
  for(var entry:context.manifests().entrySet()){var m=entry.getValue();try{m.requireApplicable();}catch(RuntimeException e){add(findings,ValidationReport.Severity.FATAL,"CORRUPT_PLAN",entry.getKey().name());}var ref=context.run().plans().get(entry.getKey());if(ref==null||!ref.planId().equals(m.planId())||!ref.fingerprint().equals(m.rootFingerprint()))add(findings,ValidationReport.Severity.FATAL,"STALE_UPSTREAM_IDENTITY",entry.getKey().name());if(!m.profile().equals(context.run().profile()))add(findings,ValidationReport.Severity.FATAL,"PROFILE_MISMATCH",entry.getKey().name());}
  var owners=new TreeMap<EnvironmentalMutation.Position,PassId>();for(var m:context.mutations().stream().sorted(Comparator.comparing(EnvironmentalMutation::position).thenComparing(x->x.pass().ordinal())).toList()){if(!context.registeredStates().contains(m.afterState()))add(findings,ValidationReport.Severity.FATAL,"MISSING_REGISTRY_ID",m.afterState());if(context.protectedPositions().contains(m.position()))add(findings,ValidationReport.Severity.FATAL,"PROTECTED_ZONE_CONFLICT",m.position().toString());var prior=owners.putIfAbsent(m.position(),m.pass());if(prior!=null&&prior!=m.pass())add(findings,ValidationReport.Severity.FATAL,"PASS_COLLISION",prior+"/"+m.pass());if((m.pass()==PassId.FLORA||m.pass()==PassId.TREES)&&context.reservations().reserved(m.position()))add(findings,ValidationReport.Severity.FATAL,"RESERVED_ZONE_OVERLAP",m.pass().name());}
  if(context.structureEnvelopeViolation())add(findings,ValidationReport.Severity.FATAL,"STRUCTURE_ENVELOPE_VIOLATION","");findings.sort(Comparator.comparing((ValidationReport.Finding f)->f.severity().ordinal()).thenComparing(ValidationReport.Finding::code).thenComparing(ValidationReport.Finding::message));return new ValidationReport(findings);
 }
 private static void add(List<ValidationReport.Finding> out,ValidationReport.Severity severity,String code,String detail){out.add(new ValidationReport.Finding(severity,code,detail));}
}
