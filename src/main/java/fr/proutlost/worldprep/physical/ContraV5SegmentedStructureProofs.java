package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.PerimeterExecution;
import fr.proutlost.worldprep.orchestration.PerimeterGroups;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only replay of all 17 immutable structure-sector WALs, 1024 group receipts,
 * placement plans and native disk-readback receipts. This gate does NOT read Anvil
 * block bytes itself and can NEVER certify V5 or substitute for a cold Anvil audit.
 * Missing, modified, extra or contradictory group evidence fails closed.
 */
public final class ContraV5SegmentedStructureProofs {
    public static final String STATUS = "STRUCTURE_WAL_CHAIN_RECHECKED_COLD_ANVIL_AND_V5_PENDING";
    public record Child(int ordinal, String areaId, UUID batch, long groups, long chunks,
                        long placed, String terminal, String aggregateSha256) {}
    public record Summary(int version, UUID qualification, UUID ecology, String requestSha256,
                          String partitionSha256, long groups, long chunks, long placed,
                          List<Child> children, String status) {
        public Summary { children = List.copyOf(children); }
    }

    /** Pure identity/coverage gate, deliberately not a physical certification. */
    public static Summary validate(ContraV5SegmentedManifest.Manifest manifest,
                                   List<Child> proofs) throws IOException {
        try { ContraV5SegmentedManifest.verify(manifest); }
        catch (RuntimeException e) { throw new IOException("Pinned Contra 17-sector manifest changed", e); }
        if (proofs == null || proofs.size() != 17)
            throw new IOException("All 17 exact child proofs are required");
        long totalGroups = 0, totalChunks = 0, totalPlaced = 0;
        for (int i = 0; i < 17; i++) {
            var expected = manifest.children().get(i);
            var actual = Objects.requireNonNull(proofs.get(i), "Missing child proof");
            if (actual.ordinal() != i || actual.ordinal() != expected.ordinal()
                || !expected.areaId().equals(actual.areaId())
                || !expected.structureBatch().equals(actual.batch())
                || actual.groups() != expected.groups() || actual.chunks() != expected.chunks()
                || actual.groups() < 1 || actual.groups() > 64
                || actual.placed() < 1 || actual.placed() > actual.groups()
                || !sha(actual.terminal()) || !sha(actual.aggregateSha256()))
                throw new IOException("Sector proof identity/coverage/refusal mismatch at " + i);
            totalGroups = Math.addExact(totalGroups, actual.groups());
            totalChunks = Math.addExact(totalChunks, actual.chunks());
            totalPlaced = Math.addExact(totalPlaced, actual.placed());
        }
        if (totalGroups != 1024 || totalChunks != 64516 || totalPlaced < 1)
            throw new IOException("Incomplete V5 sectors or no genuinely placed structure");
        return new Summary(1,manifest.qualification(),manifest.ecology(),
            manifest.frozenRequestSha256(),manifest.partitionSha256(),
            totalGroups,totalChunks,totalPlaced,proofs,STATUS);
    }
    private static boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
    private static PhysicalStore retained(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing exact native WAL directory " + path);
        for (Path current = path.toAbsolutePath(); current != null; current=current.getParent())
            if (Files.isSymbolicLink(current))
                throw new IOException("Symlink in retained native WAL path " + current);
        return new PhysicalStore(path);
    }
    private static IOException conflict(int sector,long group,String reason) {
        return new IOException("Contra sealed sector "+sector+" group "+group+" conflict: "+reason);
    }

    /**
     * Inspects ONLY previously written on-disk records. No child tasks or Minecraft
     * runtime are created. The enclosing native phase must additionally validate
     * the active world against Anvil, the global sealed binding and cold backups.
     */
    public static Summary audit(ContraV5SegmentedManifest.Manifest manifest,
                                Path segmentedRoot,String exactFrozenRequest) throws IOException {
        if (exactFrozenRequest == null
            || !PhysicalStore.sha(exactFrozenRequest).equals(manifest.frozenRequestSha256()))
            throw new IOException("Frozen global qualification bytes differ");
        retained(segmentedRoot);
        var manifestStore = retained(segmentedRoot);
        if (!manifest.equals(manifestStore.read("manifest",ContraV5SegmentedManifest.Manifest.class)))
            throw new IOException("Stored 17-child manifest substituted");
        var results = new ArrayList<Child>(17);
        for (var child : manifest.children()) {
            int i=child.ordinal();
            Path folder=segmentedRoot.resolve(String.format(java.util.Locale.ROOT,"sector-%02d",i));
            Path indexPath=folder.resolve("index");
            var index=retained(indexPath);
            var area=child.area();
            var groups=new PerimeterGroups(child.structureBatch(),PhysicalStore.sha(exactFrozenRequest),
                area.dimension(),area.minX(),area.minZ(),area.maxX(),area.maxZ());
            if (!groups.equals(index.read("perimeter",PerimeterGroups.class))
                || groups.count()!=child.groups() || groups.chunks()!=child.chunks())
                throw new IOException("Stored native sector perimeter differs at "+i);
            String previous="0".repeat(64);
            long placed=0;
            for (long ordinal=0;ordinal<groups.count();ordinal++) {
                var group=groups.group(ordinal);
                var intent=index.read("intent-"+ordinal,PerimeterExecution.Intent.class);
                var expectedIntent=new PerimeterExecution.Intent(1,groups.batch(),ordinal,
                    group.operation(),groups.configurationSha256());
                if(!expectedIntent.equals(intent))throw conflict(i,ordinal,"immutable child intent");
                var receipt=index.read("group-"+ordinal,PerimeterExecution.Receipt.class);
                if(receipt.version()!=1 || !groups.batch().equals(receipt.parent())
                    || receipt.ordinal()!=ordinal || !group.operation().equals(receipt.child())
                    || !previous.equals(receipt.previous())
                    || !PhysicalStore.sha(receipt.evidence()).equals(receipt.sha256()))
                    throw conflict(i,ordinal,"hash-chained group receipt");
                var store=retained(folder.resolve("group-"+ordinal));
                if(store.exists("rollback-intent"))
                    throw conflict(i,ordinal,"rollback intent retained");
                if(store.exists("plan")) {
                    if(store.exists("empty-survey"))throw conflict(i,ordinal,"both placement and refusal");
                    var plan=store.read("plan",NativeStructurePass.Plan.class);
                    var nativeDisk=store.read("disk-verified",NativeStructureAudit.Receipt.class);
                    var evidence=store.read("evidence",NativePerimeterStructures.Evidence.class);
                    if(!plan.operation().equals(group.operation()) ||
                       !area.dimension().equals(plan.dimension()) ||
                       !"PLACED_VERIFIED".equals(nativeDisk.status()) ||
                       !plan.operation().equals(nativeDisk.operation()) ||
                       !nativeDisk.planSha256().equals(PhysicalStore.sha(store.readText("plan"))) ||
                       !evidence.child().equals(group.operation().toString()) ||
                       evidence.ordinal()!=ordinal ||
                       !"PLACED_VERIFIED".equals(evidence.disposition()) ||
                       !evidence.planSha().equals(nativeDisk.planSha256()) ||
                       !evidence.diskSha().equals(PhysicalStore.sha(store.readText("disk-verified"))) ||
                       !receipt.evidence().equals(store.readText("evidence")))
                        throw conflict(i,ordinal,"unsealed placement or Anvil receipt");
                    for(var cell:plan.cells())
                        if(cell.x()<group.minX()||cell.x()>group.maxX()
                            ||cell.z()<group.minZ()||cell.z()>group.maxZ())
                            throw conflict(i,ordinal,"placement outside child group");
                    placed++;
                } else {
                    if(store.exists("disk-verified")||store.exists("evidence"))
                        throw conflict(i,ordinal,"unowned physical receipt");
                    var refusal=store.read("empty-survey",NativePerimeterStructures.Evidence.class);
                    if(refusal.ordinal()!=ordinal ||
                        !group.operation().toString().equals(refusal.child()) ||
                        !"NO_ELIGIBLE_EXACT_SITE".equals(refusal.disposition()) ||
                        !receipt.evidence().equals(store.readText("empty-survey")))
                        throw conflict(i,ordinal,"missing exact native no-site refusal");
                }
                previous=PhysicalStore.sha(index.readText("group-"+ordinal));
            }
            var aggregate=index.read("aggregate",PerimeterExecution.Aggregate.class);
            if(aggregate.version()!=1 || !groups.batch().equals(aggregate.parent())
                || aggregate.groups()!=groups.count() || aggregate.chunks()!=groups.chunks()
                || !previous.equals(aggregate.terminal())
                || !"GROUP_EVIDENCE_COMPLETE_NOT_MAP_READY".equals(aggregate.status()))
                throw new IOException("Native sector aggregate incomplete/changed "+i);
            results.add(new Child(i,child.areaId(),child.structureBatch(),groups.count(),
                groups.chunks(),placed,previous,PhysicalStore.sha(index.readText("aggregate"))));
        }
        return validate(manifest,results);
    }
    private ContraV5SegmentedStructureProofs() {}
}
