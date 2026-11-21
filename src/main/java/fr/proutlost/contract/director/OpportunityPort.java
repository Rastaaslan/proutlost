package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Offers only, never assignment commands. Withdrawal prevents new admission and preserves safe cleanup. */
public interface OpportunityPort {
    void publish(QuestEventOpportunity opportunity);
    void revise(OpportunityRef expected, QuestEventOpportunity replacement);
    void withdraw(OpportunityRef opportunity);
    void acknowledge(OfferAcknowledgement acknowledgement);
}
