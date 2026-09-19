package fr.proutlost.worldprep.structure;

import fr.proutlost.worldprep.biome.v2.TerrainSemantic;
import java.util.Set;

/** Verified materialized envelope and automatic allocation policy for one registry structure. */
public record StructureCapability(String structureId, StructureSupportStatus status, String adapterFingerprint,
        int width, int height, int depth, int clearance, int minimumSpacing, int rarityDivisor,
        Set<TerrainSemantic> eligibleTerrain) {
    public StructureCapability {
        if (structureId == null || structureId.isBlank() || status == null || adapterFingerprint == null
                || adapterFingerprint.isBlank() || width < 1 || height < 1 || depth < 1 || clearance < 0
                || minimumSpacing < 0 || rarityDivisor < 1 || eligibleTerrain == null || eligibleTerrain.isEmpty()) {
            throw new IllegalArgumentException("Invalid structure capability");
        }
        eligibleTerrain = Set.copyOf(eligibleTerrain);
    }
}
