package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Bounded privacy-filtered structured diagnostics. False means capacity exhausted: privileged writes must pause. */
public interface DiagnosticsSink {
    boolean emit(Diagnostic diagnostic);
    void increment(ReasonCode reason);
}
