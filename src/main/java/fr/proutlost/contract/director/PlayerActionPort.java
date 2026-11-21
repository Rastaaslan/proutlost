package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Context and authoritative result feed only. Cannot execute actions on behalf of players. */
public interface PlayerActionPort {
    void publishContext(PlayerActionContext context);
    List<Observation> results(Id world, long afterSequence, int limit);
}
