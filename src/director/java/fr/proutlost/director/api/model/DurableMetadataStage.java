package fr.proutlost.director.api.model;
import java.util.*;
/** J04-owned hidden aggregate page reference. */
public record DurableMetadataStage(LocationTransferManifest transfer,int pageIndex,int pageCount,BlobRef page) implements DurableRecord {
    public DurableMetadataStage {Objects.requireNonNull(transfer);Objects.requireNonNull(page);
        Checks.require(pageCount>0&&pageCount<=1024&&pageIndex>=0&&pageIndex<pageCount,"metadata stage page");
        Checks.require(page.bytes()>0&&page.bytes()<=1048576&&page.codecVersion()==1
            &&page.codecId().equals(new ResourceId("proutlost_director:metadata_generation_page")),"metadata stage codec/size");}
}
