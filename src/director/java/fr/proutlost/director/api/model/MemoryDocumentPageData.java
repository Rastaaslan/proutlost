package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Matched J07 wire1.1 transport fragment of one immutable AdminRow. It is not
 * a partial RegionRecord and cannot be rendered as complete semantic data. */
public record MemoryDocumentPageData(Fingerprint documentHash,int documentIndex,int documentCount,
        int documentBytes,int byteOffset,String bytesBase64) implements AdminData {
    public static final int MAX_DOCUMENT_BYTES=1_048_576,MAX_FRAGMENT_BYTES=10_240;
    public MemoryDocumentPageData {
        Objects.requireNonNull(documentHash);Objects.requireNonNull(bytesBase64);
        require(documentCount>0&&documentCount<=4096&&documentIndex>=0&&documentIndex<documentCount,"memory document index");
        require(documentBytes>0&&documentBytes<=MAX_DOCUMENT_BYTES&&byteOffset>=0&&byteOffset<documentBytes,"memory document range");
        require(bytesBase64.length()<=((MAX_FRAGMENT_BYTES+2)/3)*4,"memory fragment text bound");
        byte[] bytes=Base64.getDecoder().decode(bytesBase64);
        require(bytes.length>0&&bytes.length<=MAX_FRAGMENT_BYTES&&byteOffset+bytes.length<=documentBytes
            &&Base64.getEncoder().encodeToString(bytes).equals(bytesBase64),"memory fragment bytes/range");
    }
}
