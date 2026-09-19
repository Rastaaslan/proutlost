package fr.proutlost.worldprep.structure;
public record StructureCapability(String structureId,StructureSupportStatus status,String adapterFingerprint,int width,int depth,int clearance) {
 public StructureCapability { if(structureId==null||structureId.isBlank()||status==null||adapterFingerprint==null||adapterFingerprint.isBlank()||width<1||depth<1||clearance<0)throw new IllegalArgumentException("Invalid structure capability"); }
}
