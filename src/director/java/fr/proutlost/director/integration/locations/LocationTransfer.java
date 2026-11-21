package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.LocationTransferContract;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Public compatibility facade for the shared pure G0 wire contract.
 * Transport/registry implementations retain this API; J04 depends only on API.
 */
public final class LocationTransfer {
    private LocationTransfer() {}
    public static final int SOURCE_BYTES=LocationTransferContract.SOURCE_BYTES,
        PAGE_BYTES=LocationTransferContract.PAGE_BYTES,WIRE_BYTES=LocationTransferContract.WIRE_BYTES;
    public record Prepared(LocationTransferManifest manifest,List<LocationTransferPage> pages) {
        public Prepared {Objects.requireNonNull(manifest);pages=List.copyOf(pages);}
    }
    public static Fingerprint identity(LocationTransferManifest manifest){return LocationTransferContract.identity(manifest);}
    public static Prepared prepare(LocationImport source){
        var prepared=LocationTransferContract.prepare(source);return new Prepared(prepared.manifest(),prepared.pages());
    }
    public static byte[] fragment(LocationTransferManifest manifest,LocationTransferPage page){return LocationTransferContract.fragment(manifest,page);}
    public static void matches(LocationTransferManifest manifest,LocationImport input,byte[] bytes){LocationTransferContract.matches(manifest,input,bytes);}
}
