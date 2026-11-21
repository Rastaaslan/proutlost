package fr.proutlost.worldprep.validation;
import java.util.*;
/** Non-mutating validation result. Any FATAL finding gates production apply-all/readiness. */
public record ValidationReport(List<Finding> findings){
 public enum Severity{INFO,WARNING,ERROR,FATAL}
 public record Finding(Severity severity,String code,String message){}
 public ValidationReport{findings=List.copyOf(findings);}
 public boolean allowsProductionApply(){return findings.stream().noneMatch(f->f.severity()==Severity.FATAL);}
 public Map<Severity,Long> counts(){var result=new EnumMap<Severity,Long>(Severity.class);for(var s:Severity.values())result.put(s,findings.stream().filter(f->f.severity()==s).count());return Collections.unmodifiableMap(result);}
}
