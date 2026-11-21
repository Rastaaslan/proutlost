package fr.proutlost.director.api.codec;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure shared G0 wire/identity contract. No registry, writer, file IO or native authority. */
public final class LocationTransferContract {
    private LocationTransferContract() {}
    public static final int SOURCE_BYTES = 1_048_576, PAGE_BYTES = 20_000, WIRE_BYTES = 32_768;
    public record Prepared(LocationTransferManifest manifest, List<LocationTransferPage> pages) {
        public Prepared { Objects.requireNonNull(manifest); pages = List.copyOf(pages); }
    }
    public static Fingerprint digest(LocationImport input) {
        var data = new HashMap<>(((JsonValue.ObjectValue)CanonicalJsonV1.decode(new ModelCodec<>(LocationImport.class).encode(input))).values());
        data.remove("export_digest");
        return BlobContent.hash(CanonicalJsonV1.encode(new JsonValue.ObjectValue(data)));
    }
    public static Fingerprint identity(LocationTransferManifest manifest) {
        return BlobContent.hash(new ModelCodec<>(LocationTransferManifest.class).encode(manifest));
    }
    public static Prepared prepare(LocationImport source) {
        Objects.requireNonNull(source);
        Checks.require(source.exportDigest().equals(digest(source)), "transfer source digest");
        byte[] bytes = new ModelCodec<>(LocationImport.class).encode(source);
        Checks.require(bytes.length > 0 && bytes.length <= SOURCE_BYTES, "transfer complete source byte bound");
        var fragments = new ArrayList<byte[]>(); var hashes = new ArrayList<Fingerprint>();
        for (int offset = 0; offset < bytes.length; offset += PAGE_BYTES) {
            byte[] fragment = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + PAGE_BYTES));
            fragments.add(fragment); hashes.add(BlobContent.hash(fragment));
        }
        var manifest = new LocationTransferManifest(1, source.worldId(), source.sourceNamespace(),
                source.exportRevision(), source.exportDigest(), BlobContent.hash(bytes), bytes.length,
                Math.addExact(Math.addExact(source.regions().size(), source.locations().size()), source.anchors().size()), hashes);
        var id = identity(manifest); var pages = new ArrayList<LocationTransferPage>();
        for (int i = 0; i < fragments.size(); i++) {
            var page = new LocationTransferPage(id, i, Base64.getEncoder().encodeToString(fragments.get(i)));
            Checks.require(new ModelCodec<>(LocationTransferPage.class).encode(page).length <= WIRE_BYTES, "transfer wire bound");
            pages.add(page);
        }
        return new Prepared(manifest, pages);
    }
    public static byte[] fragment(LocationTransferManifest manifest, LocationTransferPage page) {
        Checks.require(identity(manifest).equals(page.manifestSha256()) && page.pageIndex() < manifest.pageHashes().size(),
                "transfer page generation/index mismatch");
        byte[] bytes = Base64.getDecoder().decode(page.bytesBase64());
        Checks.require(Base64.getEncoder().encodeToString(bytes).equals(page.bytesBase64()), "transfer canonical base64");
        int expected = page.pageIndex() + 1 == manifest.pageHashes().size()
                ? manifest.sourceBytes() - page.pageIndex() * PAGE_BYTES : PAGE_BYTES;
        Checks.require(bytes.length == expected && BlobContent.hash(bytes).equals(manifest.pageHashes().get(page.pageIndex())),
                "transfer page length/hash mismatch");
        return bytes;
    }
    public static void matches(LocationTransferManifest manifest, LocationImport input, byte[] bytes) {
        Checks.require(manifest.worldId().equals(input.worldId()) && manifest.sourceNamespace().equals(input.sourceNamespace())
                && manifest.exportRevision().equals(input.exportRevision()) && manifest.exportDigest().equals(input.exportDigest())
                && manifest.entryCount() == (long) input.regions().size() + input.locations().size() + input.anchors().size()
                && manifest.sourceBytes() == bytes.length && manifest.sourceSha256().equals(BlobContent.hash(bytes))
                && Arrays.equals(bytes, new ModelCodec<>(LocationImport.class).encode(input))
                && input.exportDigest().equals(digest(input)), "transfer complete source mismatch");
    }
}
