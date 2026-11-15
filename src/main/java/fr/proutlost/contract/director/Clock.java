package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Server-issued UTC, monotonic deadlines and persisted logical time. Offline time never advances logical cooldown. */
public interface Clock {
    ClockSnapshot snapshot();
}
