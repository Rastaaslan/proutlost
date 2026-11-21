package fr.proutlost.contract.director;

import java.util.*;
import java.time.Instant;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Immutable D0 contract; wire field names follow the frozen design. */
public record QuestEventOpportunity(
    Id world_id,
    SchemaVersion schema_version,
    Id opportunity_id,
    Revision revision,
    Id publisher,
    Set<QuestScope> quest_scopes,
    AudienceScope audience,
    List<EvidenceRef> context,
    EventNeed need,
    Set<Id> acceptable_fact_types,
    Instant valid_from,
    @Nullable Instant expires_at,
    WithdrawalPolicy withdrawal_policy) {
    public QuestEventOpportunity {
        Objects.requireNonNull(world_id, "world_id");
        Objects.requireNonNull(schema_version, "schema_version");
        Objects.requireNonNull(opportunity_id, "opportunity_id");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(publisher, "publisher");
        quest_scopes = Checks.copy(quest_scopes);
        Objects.requireNonNull(audience, "audience");
        context = Checks.copy(context);
        Objects.requireNonNull(need, "need");
        acceptable_fact_types = Checks.copy(acceptable_fact_types);
        Objects.requireNonNull(valid_from, "valid_from");
        Objects.requireNonNull(withdrawal_policy, "withdrawal_policy");
        Checks.require(schema_version.equals(SchemaVersion.BASE), "Wrong record schema version");
        Checks.require(world_id.equals(need.world_id()), "Cross-world offer"); Checks.require(expires_at == null || !expires_at.isBefore(valid_from), "Offer time order");
    }
}
