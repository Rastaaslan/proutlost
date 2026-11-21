package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Targeted versioned packets; enforce pinned audience, epoch, sequence, TTL and STOP tombstones. No fact authority. */
public interface PerceptionTransport {
    void send(TargetedPerceptionPayload payload, AudienceScope audience);
    void stop(TargetedPerceptionPayload payload, AudienceScope audience);
}
