package fr.proutlost.director.api.port;
/** Bootstrap obtains a separate read-only object, never the owning writer. */
public interface MetadataReadPortProvider {MetadataReadPort metadataReads();}
