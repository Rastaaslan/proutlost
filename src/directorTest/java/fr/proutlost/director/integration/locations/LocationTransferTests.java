package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real private-filesystem and abrupt-JVM-death qualification. Synthetic G0 only; no D0/native claim. */
public final class LocationTransferTests {
    private LocationTransferTests() {}
    private static final WorldId WORLD = new WorldId(new UUID(7, 7));
    private static final DimensionId DIM = new DimensionId("minecraft:overworld");
    private static final ResourceId SOURCE = new ResourceId("fixture:transport");
    private static final ModelCodec<LocationTransferManifest> MANIFEST = new ModelCodec<>(LocationTransferManifest.class);
    private static final ModelCodec<LocationTransferPage> PAGE = new ModelCodec<>(LocationTransferPage.class);
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
    private static void refused(CompletionStage<?> stage, String why) throws Exception {
        try { await(stage); throw new AssertionError("accepted " + why); }
        catch (ExecutionException expected) { assertions++; }
    }
    private static LocationImport source(int count, int offset, long revision, ResourceId namespace) {
        var regions = new ArrayList<ImportedRegion>(); var locations = new ArrayList<ImportedLocation>();
        var anchors = new ArrayList<ImportedAnchor>();
        for (int i = offset; i < offset + count; i++) {
            var rid = new RegionId(new UUID(20, i)); var lid = new LocationId(new UUID(21, i));
            var aid = new AnchorId(new UUID(22, i)); var box = new ImportBounds(i, 1, i, i + 1, 2, i + 1);
            regions.add(new ImportedRegion(rid, DIM, box, Set.of()));
            locations.add(new ImportedLocation(lid, "fixture-" + i, Set.of(rid), DIM, box, Set.of(new TagId("fixture:site"))));
            anchors.add(new ImportedAnchor(aid, lid, DIM, new ImportPosition(i, 1, i), Set.of()));
        }
        var draft = new LocationImport(1, WORLD, namespace, new Revision(revision),
                new Fingerprint("0".repeat(64)), regions, locations, anchors);
        return new LocationImport(1, WORLD, namespace, draft.exportRevision(), StandaloneLocationImporter.digest(draft), regions, locations, anchors);
    }
    private static StandaloneLocationImporter registry(Path root, Executor writer, int bytes, int entries) throws Exception {
        return await(StandaloneLocationImporter.open(root.resolve("registry"), WORLD,
                Map.of(DIM, new ImportBounds(-50000, -64, -50000, 50000, 320, 50000)),
                t -> t.value().equals("fixture:site"), writer, bytes, entries));
    }
    private static void pages(LocationTransferInbox inbox, LocationTransfer.Prepared source) throws Exception {
        await(inbox.begin(MANIFEST.encode(source.manifest())));
        for (var page : source.pages()) await(inbox.accept(PAGE.encode(page)));
    }
    private static void ordinary(Path root) throws Exception {
        var writer = Executors.newSingleThreadExecutor();
        var registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        var old = source(2, 0, 1, SOURCE); check(await(registry.submit(old)).status() == AckStatus.DURABLE, "old revision committed");
        var input = source(180, 50, 2, SOURCE); var transfer = LocationTransfer.prepare(input); var id = LocationTransfer.identity(transfer.manifest());
        check(transfer.pages().size() > 3, "whole source exceeds multiple small ingress messages");
        try {
            try { StandaloneLocationImporter.requireNativeEnvelope(input); throw new AssertionError("native cap bypassed"); }
            catch (IllegalArgumentException expected) { assertions++; }
            await(inbox.begin(MANIFEST.encode(transfer.manifest())));
            refused(inbox.commit(id), "missing pages");
            check(registry.snapshot().current().get(SOURCE).equals(old), "incomplete transfer keeps old revision");
            var different = LocationTransfer.prepare(source(180, 50, 3, SOURCE));
            refused(inbox.begin(MANIFEST.encode(different.manifest())), "replacement of unfinished generation");
            var order = new ArrayList<>(transfer.pages()); Collections.reverse(order);
            for (var page : order) {
                check(PAGE.encode(page).length <= 32768, "every actual wire message bounded");
                await(inbox.accept(PAGE.encode(page))); await(inbox.accept(PAGE.encode(page)));
                check(registry.snapshot().current().get(SOURCE).equals(old), "staged and duplicate pages never publish revisions");
            }
            var first = transfer.pages().getFirst(); byte[] changed = Base64.getDecoder().decode(first.bytesBase64()); changed[0] ^= 1;
            refused(inbox.accept(PAGE.encode(new LocationTransferPage(id, 0, Base64.getEncoder().encodeToString(changed)))), "contradictory page");
            refused(inbox.accept(new byte[32769]), "oversized page");
            refused(inbox.begin(new byte[32769]), "oversized manifest");
            refused(inbox.accept(PAGE.encode(new LocationTransferPage(new Fingerprint("1".repeat(64)), 0, first.bytesBase64()))), "foreign generation");
            var wrongWorld = new LocationTransferManifest(1, new WorldId(new UUID(9, 9)), SOURCE, transfer.manifest().exportRevision(),
                    transfer.manifest().exportDigest(), transfer.manifest().sourceSha256(), transfer.manifest().sourceBytes(),
                    transfer.manifest().entryCount(), transfer.manifest().pageHashes());
            refused(inbox.begin(MANIFEST.encode(wrongWorld)), "wrong world");
            check(await(inbox.commit(id)).status() == AckStatus.DURABLE, "whole revision committed by actual registry");
            var retained = registry.snapshot();
            check(retained.current().get(SOURCE).equals(input), "all source entries exact");
            check(retained.historicalInactive().size() == 6, "all absent old identities archived");
            check(await(inbox.commit(id)).status() == AckStatus.DURABLE, "duplicate complete commit idempotent");
            var next = source(1, 500, 3, SOURCE); var nextTransfer = LocationTransfer.prepare(next);
            await(inbox.begin(MANIFEST.encode(nextTransfer.manifest())));
            check(registry.snapshot().current().get(SOURCE).equals(input), "next manifest never retires current source");
            for (var page : nextTransfer.pages()) await(inbox.accept(PAGE.encode(page)));
            check(await(inbox.commit(LocationTransfer.identity(nextTransfer.manifest()))).status() == AckStatus.DURABLE, "new full revision");
            check(retained.current().get(SOURCE).equals(input), "retained active-instance snapshot immutable");
            check(registry.snapshot().historicalInactive().size() == 546, "entire replacement preserves old history");
        } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
        writer = Executors.newSingleThreadExecutor(); registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        try { check(registry.snapshot().current().get(SOURCE).equals(source(1, 500, 3, SOURCE)), "cold current revision exact");
            check(registry.snapshot().historicalInactive().size() == 546, "cold history exact");
            refused(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer), "second staging writer");
        } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
    }
    private static void guarded(Path root) throws Exception {
        var writer = Executors.newSingleThreadExecutor();
        var registry = registry(root, writer, 32768, 96);
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        try {
            var old = source(2, 0, 1, SOURCE); await(registry.submit(old));
            var large = LocationTransfer.prepare(source(120, 10, 2, SOURCE)); await(inbox.begin(MANIFEST.encode(large.manifest())));
            for (var page : large.pages()) await(inbox.accept(PAGE.encode(page)));
            refused(inbox.commit(LocationTransfer.identity(large.manifest())), "original small native registry cap");
            check(registry.snapshot().current().get(SOURCE).equals(old), "staging never weakens receiver capacity");
            check(!Files.exists(root.resolve("inbox").resolve(LocationTransfer.identity(large.manifest()).value()).resolve("REGISTRY_COMMITTED.json")), "no forged durable receipt");
        } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
    }
    private static void maximumAndCapacity(Path root) throws Exception {
        var large = LocationTransfer.prepare(source(1000, 0, 1, SOURCE));
        check(large.manifest().sourceBytes() > 650000 && large.pages().size() > 30, "large independent source exercised");
        var writer = Executors.newSingleThreadExecutor(); var registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        try {
            pages(inbox, large); check(await(inbox.commit(LocationTransfer.identity(large.manifest()))).status() == AckStatus.DURABLE, "large registry complete transfer");
            check(registry.snapshot().current().get(SOURCE).locations().size() == 1000, "no page discarded on large transfer");
            for (int i = 1; i < 32; i++) {
                var small = LocationTransfer.prepare(source(0, 0, 1, new ResourceId("fixture:source_" + i)));
                await(inbox.begin(MANIFEST.encode(small.manifest())));
            }
            var overflow = LocationTransfer.prepare(source(0, 0, 1, new ResourceId("fixture:source_32")));
            refused(inbox.begin(MANIFEST.encode(overflow.manifest())), "33rd source");
            try { LocationTransfer.prepare(source(2000, 0, 1, SOURCE)); throw new AssertionError("oversized source accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
    }
    private static void crashChild(Path root, String point) throws Exception {
        var writer = Executors.newSingleThreadExecutor(); var registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        if (registry.snapshot().current().isEmpty()) await(registry.submit(source(2, 0, 1, SOURCE)));
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer,
                observed -> { if (observed.equals(point)) Runtime.getRuntime().halt(72); }));
        var next = LocationTransfer.prepare(source(80, 50, 2, SOURCE)); pages(inbox, next);
        await(inbox.commit(LocationTransfer.identity(next.manifest())));
        throw new AssertionError("crash point not reached " + point);
    }
    private static void crashRecovery(Path root) throws Exception {
        var next = LocationTransfer.prepare(source(80, 50, 2, SOURCE));
        var points = new ArrayList<>(List.of("MANIFEST_FILE_FORCED", "CURRENT_POINTER_FORCED"));
        for (int i = 0; i < next.pages().size(); i++) points.add("PAGE_FORCED:" + i);
        points.add("REGISTRY_DURABLE_BEFORE_RECEIPT"); points.add("TRANSFER_RECEIPT_FORCED");
        for (String point : points) {
            Path dir = root.resolve(point.replace(':', '-')); Files.createDirectories(dir);
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-Xmx192m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                    LocationTransferTests.class.getName(), "crash", dir.toString(), point).redirectErrorStream(true).start();
            boolean ended = process.waitFor(30, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            check(ended && process.exitValue() == 72, "actual abrupt child exit at " + point + " " + new String(process.getInputStream().readAllBytes()));
            var writer = Executors.newSingleThreadExecutor(); var registry = registry(dir, writer, LocationTransfer.SOURCE_BYTES, 20000);
            var inbox = await(LocationTransferInbox.open(dir.resolve("inbox"), WORLD, registry, writer));
            try {
                var before = registry.snapshot().current().get(SOURCE);
                check(before.equals(source(2, 0, 1, SOURCE)) || before.equals(source(80, 50, 2, SOURCE)), "crash exposes only complete old/new revision");
                pages(inbox, next);
                check(await(inbox.commit(LocationTransfer.identity(next.manifest()))).status() == AckStatus.DURABLE, "same-generation resume at " + point);
                check(registry.snapshot().current().get(SOURCE).equals(source(80, 50, 2, SOURCE)), "cold resumed source exact");
                check(registry.snapshot().historicalInactive().size() == 6, "history not duplicated by crash resume");
            } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
        }
        System.out.println("ABRUPT_JVM_CRASH_POINTS=" + points.size());
    }
    private static void corruption(Path root) throws Exception {
        var writer = Executors.newSingleThreadExecutor(); var registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        var next = LocationTransfer.prepare(source(80, 50, 2, SOURCE));
        pages(inbox, next); await(inbox.closeAsync());
        Path file = root.resolve("inbox").resolve(LocationTransfer.identity(next.manifest()).value()).resolve("page-0.bin");
        byte[] bytes = Files.readAllBytes(file); bytes[0] ^= 1; Files.write(file, bytes);
        refused(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer), "cold corrupted page");
        check(registry.snapshot().current().isEmpty(), "corrupt staging never changes registry");
        await(registry.closeAsync()); writer.shutdown();
        Path outside = Files.createDirectories(root.resolve("outside")); Path link = root.resolve("linked"); Files.createSymbolicLink(link, outside);
        writer = Executors.newSingleThreadExecutor(); registry = registry(root.resolve("symlink-registry"), writer, LocationTransfer.SOURCE_BYTES, 20000);
        refused(LocationTransferInbox.open(link, WORLD, registry, writer), "symlink staging root");
        try (var files = Files.list(outside)) { check(files.findAny().isEmpty(), "symlink refusal before writes"); }
        await(registry.closeAsync()); writer.shutdown();
    }
    private static void cancellationAndSemanticRefusal(Path root) throws Exception {
        var writer = Executors.newSingleThreadExecutor(); var registry = registry(root, writer, LocationTransfer.SOURCE_BYTES, 20000);
        var inbox = await(LocationTransferInbox.open(root.resolve("inbox"), WORLD, registry, writer));
        try {
            var old = source(2, 0, 1, SOURCE); await(registry.submit(old));
            var good = source(1, 50, 2, SOURCE); var l = good.locations().getFirst();
            var badLocation = new ImportedLocation(l.locationId(), l.externalId(), l.regionIds(), l.dimension(), l.bounds(), Set.of(new TagId("fixture:uninstalled")));
            var unsigned = new LocationImport(1, WORLD, SOURCE, good.exportRevision(), good.exportDigest(), good.regions(), List.of(badLocation), good.anchors());
            var bad = new LocationImport(1, WORLD, SOURCE, good.exportRevision(), StandaloneLocationImporter.digest(unsigned), unsigned.regions(), unsigned.locations(), unsigned.anchors());
            var proposal = LocationTransfer.prepare(bad); var id = LocationTransfer.identity(proposal.manifest()); pages(inbox, proposal);
            refused(inbox.commit(id), "whole-source unsupported semantic tag");
            check(registry.snapshot().current().get(SOURCE).equals(old), "whole-source semantic refusal keeps old revision");
            await(inbox.cancel(id));
            check(Files.isRegularFile(root.resolve("inbox").resolve(id.value()).resolve("manifest.json")), "cancel preserves immutable source evidence");
            refused(inbox.begin(MANIFEST.encode(proposal.manifest())), "reviving cancelled generation");
            var corrected = LocationTransfer.prepare(good); pages(inbox, corrected);
            check(await(inbox.commit(LocationTransfer.identity(corrected.manifest()))).status() == AckStatus.DURABLE, "corrected source can follow explicit cancellation");
            refused(inbox.cancel(LocationTransfer.identity(corrected.manifest())), "cancel after actual registry publication");
            check(registry.snapshot().current().get(SOURCE).equals(good), "cancel never rolls back committed state");
        } finally { await(inbox.closeAsync()); await(registry.closeAsync()); writer.shutdown(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("crash")) { crashChild(Path.of(args[1]), args[2]); return; }
        Path root = args.length == 1 ? Path.of(args[0]) : Files.createTempDirectory("location-transfer-QA-");
        Files.createDirectories(root);
        ordinary(root.resolve("ordinary")); guarded(root.resolve("guarded")); maximumAndCapacity(root.resolve("large"));
        crashRecovery(root.resolve("crashes")); corruption(root.resolve("corrupt")); cancellationAndSemanticRefusal(root.resolve("cancel"));
        System.out.println("PASS LocationTransferTests assertions=" + assertions + " scope=STANDALONE_G0_REGISTRY_SYNTHETIC_NOT_NATIVE_D0");
    }
}
