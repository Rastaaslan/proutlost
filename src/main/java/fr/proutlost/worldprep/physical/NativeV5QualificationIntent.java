package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.storage.BatchManifestStore;
import java.time.Instant;
import java.util.UUID;

/** Strict immutable v5 *intent* shape, separate from legacy v3/v4.
 *
 * Qualifying and publishing a native v5 session is NOT implemented yet.
 * Pre-copy freeze is supported for a future isolated qualification only.
 * No v4 journal/request can be decoded as this type.
 */
public record NativeV5QualificationIntent(int version,boolean enabled,UUID batch,
        String area,BatchManifestStore.Header ecology,
        String backupManifest,String backupSha256,String backupRootA,String backupRootB,
        String template,MultiSpeciesFaunaPlan.Manifest multiSpecies,
        String worldId,String buildId,String revision,String validatedAt,
        String directorReceipt,String directorImporterSha256,NativeSpawnAudit.Spec spawn) {
    public NativeV5QualificationIntent {
        if(version!=5||!enabled||batch==null||area==null||area.isBlank()
            ||ecology==null||backupManifest==null||backupRootA==null||backupRootB==null
            ||template==null||multiSpecies==null||worldId==null||buildId==null
            ||revision==null||validatedAt==null||directorReceipt==null
            ||directorImporterSha256==null||spawn==null)
            throw new IllegalArgumentException("Exact v5 multi-species qualification required");
        MultiSpeciesFaunaPlan.verify(multiSpecies);
        if(!batch.equals(multiSpecies.parentBatch()))
            throw new IllegalArgumentException("Multi-species parent/batch mismatch");
        if(ecology.stages()<1||!ContraV5SectorLineage.typedIdentity(ecology,area)
            ||!template.equals("proutlost:worldprep/stone_platform_v1")
            ||!directorImporterSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Unsupported v5 frozen evidence profile");
        Instant.parse(validatedAt);
    }
}
