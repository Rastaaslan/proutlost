package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import static fr.proutlost.contract.director.Vocabulary.CatalogState;

/**
 * Qualification gate only. It neither imports, grants authority, writes receipts
 * nor opens a store. The consumer owns admission, exact durable readback and the
 * receipt. In particular a missing receipt must not prevent the consumer from
 * progressing the import that will produce it.
 */
public final class CatalogImportAdmissionGate {
    public record Receipt(int version,String worldId,String buildId,String catalogueSha256,
            String importerSourceSha256,String runtimeIdentity,String status) {}
    @FunctionalInterface public interface ReceiptReader {
        Optional<Receipt> read() throws IOException;
    }
    private final Id world,build;
    private final String providerSha;
    private String pinnedCatalog;

    public CatalogImportAdmissionGate(Id world,Id build,String providerSha) {
        this.world=Objects.requireNonNull(world);this.build=Objects.requireNonNull(build);
        if(providerSha==null||!providerSha.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Pinned actual provider bytecode SHA required");
        this.providerSha=providerSha;
    }
    public Optional<Receipt> step(String catalog,CatalogImportProbe consumer,ReceiptReader receipts)
            throws IOException {
        Objects.requireNonNull(consumer);Objects.requireNonNull(receipts);
        requireExactSource(catalog,world,build);
        if(pinnedCatalog==null)pinnedCatalog=catalog;
        require(pinnedCatalog.equals(catalog),"Director import input changed while pending");

        // Existing contradictory evidence fails before any consumer invocation.
        var before=Objects.requireNonNull(receipts.read());
        if(before.isPresent())validate(before.orElseThrow(),catalog);

        var admitted=Objects.requireNonNull(consumer.pollImportAndReadAdmitted(catalog,world,build));
        if(admitted.isEmpty())return Optional.empty();
        var snapshot=admitted.orElseThrow();
        try {CatalogValidation.requireSealed(snapshot);}
        catch(IllegalArgumentException invalid){throw new IOException("Unsealed consumer readback",invalid);}
        require(StrictCodec.encode(snapshot).equals(catalog),
            "Director admitted a different physical catalogue");

        // The consumer can still be flushing the receipt AFTER real readback.
        // Keep polling the same pinned import; never fabricate the missing file.
        var after=Objects.requireNonNull(receipts.read());
        if(after.isEmpty())return Optional.empty();
        validate(after.orElseThrow(),catalog);
        if(before.isPresent())require(before.equals(after),"Director import receipt changed during poll");
        return after;
    }
    /** Durable physical-phase pin, checked before resolving or polling any consumer. */
    public static CatalogSnapshot requireQualifiedSource(String catalog,String qualified,Id world,Id build)
            throws IOException {
        require(catalog!=null&&catalog.equals(qualified),
            "Frozen V5 physical catalogue changed before import");
        return requireExactSource(catalog,world,build);
    }
    /** Common input guard for both production and DEV polling paths. */
    public static CatalogSnapshot requireExactSource(String catalog,Id world,Id build)throws IOException {
        try {
            var source=StrictCodec.decode(catalog,CatalogSnapshot.class);
            CatalogValidation.requireSealed(source);
            require(source.state()==CatalogState.READY&&source.world_id().equals(world)
                &&source.build_id().equals(build)&&StrictCodec.encode(source).equals(catalog),
                "Expected exact sealed READY catalogue for the bound world/build");
            return source;
        } catch(IllegalArgumentException invalid) {
            throw new IOException("Invalid canonical Director import input",invalid);
        }
    }
    private void validate(Receipt r,String catalog)throws IOException {
        require(r!=null&&r.version()==1&&world.value().equals(r.worldId())
            &&build.value().equals(r.buildId())
            &&Digests.sha256(catalog).value().equals(r.catalogueSha256())
            &&providerSha.equals(r.importerSourceSha256())
            &&r.runtimeIdentity()!=null&&!r.runtimeIdentity().isBlank()
            &&"IMPORTED_REAL_ISOLATED_RUNTIME".equals(r.status()),
            "Real Director import receipt is not pinned");
    }
    private static void require(boolean condition,String message)throws IOException {
        if(!condition)throw new IOException(message);
    }
}
