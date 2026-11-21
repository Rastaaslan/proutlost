package fr.proutlost.director.api.model;
import java.util.*;
/** Original G0 bytes retained by J04, never source authority from a second registry. */
public record DurableMetadataSourcePage(LocationTransferManifest transfer,int pageIndex,BlobRef page) implements DurableRecord {
    public DurableMetadataSourcePage {Objects.requireNonNull(transfer);Objects.requireNonNull(page);
        Checks.require(pageIndex>=0&&pageIndex<transfer.pageHashes().size(),"metadata source page index");
        int expected=pageIndex+1==transfer.pageHashes().size()?transfer.sourceBytes()-pageIndex*20000:20000;
        Checks.require(page.bytes()==expected&&page.hash().equals(transfer.pageHashes().get(pageIndex))
            &&page.codecVersion()==1&&page.codecId().equals(new ResourceId("proutlost_director:location_transfer_fragment")),"metadata source fragment binding");}
}
