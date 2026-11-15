package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure hostile WAL header fixtures. No filesystem, fsync or gameplay qualification. */
public final class JournalBlobAttestationFenceTests {
    private static int checks;
    private static void check(boolean ok, String message) {
        if(!ok)throw new AssertionError(message);
        checks++;
    }
    private static JsonValue.Array header(BlobRef... refs) {
        return new JsonValue.Array(Arrays.stream(refs)
            .map(ref->StoreJson.model(BlobRef.class,ref)).toList());
    }
    private static void denied(JsonValue declared,Set<BlobRef> expected,String reason){
        try {
            JournalStore.verifiedForcedBlobs(declared,expected);
            throw new AssertionError("WAL attestation accepted: "+reason);
        }catch(IllegalArgumentException invalid){checks++;}
    }
    public static void main(String[] args) {
        var first=BlobContent.capture(new ResourceId("fixture:one"),1,new byte[]{1}).reference();
        var second=BlobContent.capture(new ResourceId("fixture:two"),1,new byte[]{2}).reference();
        check(JournalStore.verifiedForcedBlobs(header(),Set.of()).isEmpty(),
            "genuine empty attestation");
        check(JournalStore.verifiedForcedBlobs(header(first),Set.of(first)).equals(Set.of(first)),
            "exact single reference");
        check(JournalStore.verifiedForcedBlobs(header(second,first),Set.of(first,second))
                .equals(Set.of(first,second)), "exact unordered references");
        denied(header(first),Set.of(),"unreferenced blob in WAL header");
        denied(header(),Set.of(first),"missing forced blob from WAL header");
        denied(header(first,first),Set.of(first),"duplicate forced blob in WAL header");
        denied(header(first,second),Set.of(first),"extra blob among valid references");
        denied(new JsonValue.Text("not an array"),Set.of(),"non-array WAL header");
        System.out.println("PASS_J04_EXACT_WAL_BLOB_ATTESTATION_ASSERTIONS="+checks);
        System.out.println("PURE_HEADER_FIXTURE_ONLY_NO_NATIVE_FSYNC_PASS");
    }
}
