package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable health verdict. Degradation never removes reserved cleanup capacity. */
public interface HealthGate {
    HealthVerdict evaluate(Id world);
}
