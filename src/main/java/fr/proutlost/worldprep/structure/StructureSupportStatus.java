package fr.proutlost.worldprep.structure;
public enum StructureSupportStatus { EXACT_TEMPLATE, EXACT_JIGSAW, SANDBOX_MATERIALIZABLE, CUSTOM_SAFE_ADAPTER, UNSUPPORTED_OPAQUE, BLACKLISTED;
 public boolean productionSupported(){return this==EXACT_TEMPLATE||this==EXACT_JIGSAW||this==SANDBOX_MATERIALIZABLE||this==CUSTOM_SAFE_ADAPTER;}
}
