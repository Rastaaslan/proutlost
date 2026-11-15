package fr.proutlost.worldprep.physical;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** WorldPrep authoritative record of a structure actually placed and verified on disk.
 * Never exports an intent, simulation, or vanilla structure reference as physical placement.
 * This record is an input to a later Director G0 import, not an import ACK by itself.
 */
public final class NativeVerifiedStructureCatalog {
    public record Anchor(UUID anchorId, int x,int y,int z,Set<String> tags) {}
    public record LocationRecord(int schema, UUID locationId, UUID operation,
            String dimension, String template, String templateSha256,
            String planSha256, String diskReceiptSha256,
            int minX,int minY,int minZ,int maxXExclusive,int maxYExclusive,int maxZExclusive,
            Set<String> tags,List<Anchor> anchors,
            String status) {}
    /** Registered mod generator provenance is NOT a file template sha. */
    public record NativeLocationRecord(int schema,UUID locationId,UUID operation,
            String dimension,String structureRegistryId,String generationPlanSha256,
            String diskReceiptSha256,int minX,int minY,int minZ,
            int maxXExclusive,int maxYExclusive,int maxZExclusive,
            Set<String> tags,List<Anchor> anchors,String status) {}

    public static NativeLocationRecord publishNative(PhysicalStore store,Set<String> tags)throws IOException {
        if(tags==null||!tags.contains("proutlost:structure")||tags.size()>24)
            throw new IOException("Native location requires universal Director tag");
        var names=new TreeSet<String>();
        for(var tag:tags) {
            if(tag==null||!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IOException("Invalid Director tag");
            names.add(tag);
        }
        if(store.exists("rollback-intent")||!store.exists("native-disk-verified"))
            throw new IOException("Native structure not independently verified on disk");
        var declared=store.read("native-all-chunks-placed",NativeRegistryStructurePass.NativeReceipt.class);
        var evidence=store.read("native-disk-verified",NativeRegistryStructureAudit.Receipt.class);
        String planSha=PhysicalStore.sha(store.readText("native-plan"));
        if(evidence.version()!=1||!declared.operation().equals(evidence.operation())
                ||!declared.registryId().equals(evidence.registryId())
                ||!planSha.equals(evidence.nativePlanSha256())
                ||evidence.changedBlocks()<1||evidence.chunks()!=declared.totalChunks()
                ||!"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED".equals(evidence.status()))
            throw new IOException("Native physical/disk proof mismatch");
        var b=declared.actualBounds();
        UUID id=UUID.nameUUIDFromBytes(("worldprep-native-location-v1/"
            +declared.dimension()+"/"+declared.registryId()+"/"+declared.operation()).getBytes(StandardCharsets.UTF_8));
        UUID anchorId=UUID.nameUUIDFromBytes(("worldprep-native-anchor-v1/"+id).getBytes(StandardCharsets.UTF_8));
        var anchor=new Anchor(anchorId,b.minX()+(b.maxX()-b.minX())/2,b.minY(),
                b.minZ()+(b.maxZ()-b.minZ())/2,Set.of("proutlost:anchor_center"));
        var result=new NativeLocationRecord(1,id,declared.operation(),declared.dimension(),
            declared.registryId(),planSha,PhysicalStore.sha(store.readText("native-disk-verified")),
            b.minX(),b.minY(),b.minZ(),Math.addExact(b.maxX(),1),
            Math.addExact(b.maxY(),1),Math.addExact(b.maxZ(),1),
            Set.copyOf(names),List.of(anchor),"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
        store.write("director-native-location-candidate",result);
        return result;
    }
    private NativeVerifiedStructureCatalog() {}
    public static LocationRecord publish(PhysicalStore store) throws IOException {
        return publish(store,Set.of("proutlost:structure"));
    }
    public static LocationRecord publish(PhysicalStore store,Set<String> tags) throws IOException {
        if(tags==null||tags.isEmpty()||tags.size()>24)throw new IOException("Tagged structure requires finite tags");
        var validatedTags=new TreeSet<String>();
        for(var tag:tags) {
            if(tag==null||!tag.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IOException("Invalid Director tag: "+tag);
            validatedTags.add(tag);
        }
        if(!validatedTags.contains("proutlost:structure"))
            throw new IOException("Missing universal proutlost:structure tag");
        if(store.exists("rollback-intent"))throw new IOException("Rolled back structure refused");
        if(!store.exists("plan") || !store.exists("disk-verified"))
            throw new IOException("No physically verified native structure to catalog");
        var plan=store.read("plan",NativeStructurePass.Plan.class);
        var verified=store.read("disk-verified",NativeStructureAudit.Receipt.class);
        String planSha=PhysicalStore.sha(store.readText("plan"));
        String diskSha=PhysicalStore.sha(store.readText("disk-verified"));
        if(verified.version()!=1||!"PLACED_VERIFIED".equals(verified.status())
                ||!plan.operation().equals(verified.operation())||!planSha.equals(verified.planSha256())
                ||plan.cells().isEmpty())throw new IOException("Structure disk proof does not match plan");
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var cell:plan.cells()) {
            minX=Math.min(minX,cell.x()); minY=Math.min(minY,cell.y()); minZ=Math.min(minZ,cell.z());
            maxX=Math.max(maxX,cell.x()); maxY=Math.max(maxY,cell.y()); maxZ=Math.max(maxZ,cell.z());
        }
        var id=UUID.nameUUIDFromBytes(("worldprep-native-location-v1/"+plan.operation()+"/"+plan.templateSha256()).getBytes(StandardCharsets.UTF_8));
        var anchorId=UUID.nameUUIDFromBytes(("worldprep-native-anchor-center-v1/"+id).getBytes(StandardCharsets.UTF_8));
        var anchor=new Anchor(anchorId,minX+(maxX-minX)/2,minY,maxZ-(maxZ-minZ)/2,
                Set.of("proutlost:anchor_center"));
        var result=new LocationRecord(1,id,plan.operation(),plan.dimension(),plan.template(),
                plan.templateSha256(),planSha,diskSha,
                minX,minY,minZ,Math.addExact(maxX,1),Math.addExact(maxY,1),Math.addExact(maxZ,1),
                Set.copyOf(validatedTags),List.of(anchor),
                "PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED");
        store.write("director-location-candidate",result);
        return result;
    }
}
