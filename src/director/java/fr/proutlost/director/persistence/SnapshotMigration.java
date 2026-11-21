package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.JsonValue;

/** Explicit adjacent-schema migration, supplied by the owner of the target schema.
 * Implementations are pure: no world, clock, filesystem, randomness, or native Java serialization.
 * V1 is the first published schema; this release supplies no invented legacy migration.
 */
public interface SnapshotMigration {
    int sourceSchema();
    int targetSchema();
    JsonValue migrateRecord(JsonValue source);
    /** Must reject an invalid target record; never repairs by discarding unknown content. */
    void validateTarget(JsonValue target);
}
