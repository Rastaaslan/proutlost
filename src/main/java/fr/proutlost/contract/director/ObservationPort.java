package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Bounded authoritative evidence and loaded-only support/protection observations. A transport ACK is not evidence. */
public interface ObservationPort {
    List<Observation> after(Id world, long sequence, int limit);
    Optional<LocationObservation> current(Id world, LocationRef location);
}
