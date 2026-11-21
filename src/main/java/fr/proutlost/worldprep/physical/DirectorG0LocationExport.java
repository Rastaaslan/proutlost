package fr.proutlost.worldprep.physical;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Produces Director G0 schema_version=1 JSON from independently disk-verified native
 * mod structures. Matches Director CanonicalJsonV1: lexicographic object keys,
 * sorted tag Sets, compact UTF-8, digest over the object without export_digest.
 * A valid export is NOT a durable Director importer ACK.
 */
public final class DirectorG0LocationExport {
    public static final String SOURCE_NAMESPACE="proutlost:worldprep_structures";
    private DirectorG0LocationExport(){}

    public static JsonObject build(UUID directorWorldId,long exportRevision,
                 List<NativeVerifiedStructureCatalog.NativeLocationRecord> records) {
        if(directorWorldId==null||exportRevision<0||records==null||records.isEmpty()
                ||records.size()>4096)throw new IllegalArgumentException("Bounded G0 export identity required");
        var regions=new JsonArray();
        var locations=new JsonArray();
        var anchors=new JsonArray();
        var ids=new HashSet<UUID>();
        var ordered=records.stream().sorted(Comparator.comparing(v->v.locationId().toString())).toList();
        for(var record:ordered) {
            if(record==null||record.locationId()==null||record.anchors()==null
                ||record.tags()==null||record.tags().isEmpty()
                ||!record.tags().contains("proutlost:structure")
                ||!"PLACED_VERIFIED_NOT_DIRECTOR_IMPORTED".equals(record.status())
                ||!ids.add(record.locationId()))
                throw new IllegalArgumentException("Unverified/duplicate native location");
            // Preserve region membership and geometry, without copying LOCATION tags
            // into the REGION scope. Each anchor likewise keeps its own role tags.
            UUID regionId=UUID.nameUUIDFromBytes(("worldprep-structure-region-v1/"+record.locationId())
                .getBytes(StandardCharsets.UTF_8));
            if(!ids.add(regionId))throw new IllegalArgumentException("Duplicate structure region");
            var region=new JsonObject();
            region.addProperty("region_id",regionId.toString());
            region.addProperty("dimension",record.dimension());
            region.add("bounds",bounds(record.minX(),record.minY(),record.minZ(),
                record.maxXExclusive(),record.maxYExclusive(),record.maxZExclusive()));
            region.add("tags",new JsonArray()); // Structure classification has LOCATION scope.
            regions.add(region);
            var l=new JsonObject();
            l.addProperty("location_id",record.locationId().toString());
            l.addProperty("external_id",record.structureRegistryId()+"/"+record.operation());
            var memberships=new JsonArray();memberships.add(regionId.toString());
            l.add("region_ids",memberships);
            l.addProperty("dimension",record.dimension());
            l.add("bounds",bounds(record.minX(),record.minY(),record.minZ(),
                record.maxXExclusive(),record.maxYExclusive(),record.maxZExclusive()));
            l.add("tags",tags(record.tags()));
            locations.add(l);
            for(var anchor:record.anchors().stream().sorted(
                    Comparator.comparing(a->a.anchorId().toString())).toList()) {
                if(anchor.anchorId()==null||!ids.add(anchor.anchorId()))
                    throw new IllegalArgumentException("Duplicate/absent native anchor");
                if(anchor.x()<record.minX()||anchor.x()>=record.maxXExclusive()
                    ||anchor.y()<record.minY()||anchor.y()>=record.maxYExclusive()
                    ||anchor.z()<record.minZ()||anchor.z()>=record.maxZExclusive())
                    throw new IllegalArgumentException("Native anchor outside location");
                var a=new JsonObject();
                a.addProperty("anchor_id",anchor.anchorId().toString());
                a.addProperty("location_id",record.locationId().toString());
                a.addProperty("dimension",record.dimension());
                var pos=new JsonObject();
                pos.addProperty("x",anchor.x());pos.addProperty("y",anchor.y());
                pos.addProperty("z",anchor.z());
                a.add("position",pos);
                a.add("tags",tags(anchor.tags()));
                anchors.add(a);
            }
        }
        var root=new JsonObject();
        root.addProperty("schema_version",1);
        root.addProperty("world_id",directorWorldId.toString());
        root.addProperty("source_namespace",SOURCE_NAMESPACE);
        root.addProperty("export_revision",Long.toString(exportRevision));
        root.add("regions",regions);
        root.add("locations",locations);
        root.add("anchors",anchors);
        root.addProperty("export_digest",sha256(canonical(root)));
        return root;
    }

    private static JsonObject bounds(int x,int y,int z,int x2,int y2,int z2) {
        if(x>=x2||y>=y2||z>=z2)throw new IllegalArgumentException("Invalid G0 exclusive bounds");
        var v=new JsonObject();
        v.addProperty("min_x",x);v.addProperty("min_y",y);v.addProperty("min_z",z);
        v.addProperty("max_x_exclusive",x2);v.addProperty("max_y_exclusive",y2);
        v.addProperty("max_z_exclusive",z2);
        return v;
    }
    private static JsonArray tags(Set<String> set) {
        var arr=new JsonArray();
        for(var name:new TreeSet<>(set)) {
            if(name==null||!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid namespaced G0 tag");
            arr.add(name);
        }
        return arr;
    }
    public static String canonical(JsonElement value){
        StringBuilder result=new StringBuilder();appendCanonical(result,value);
        return result.toString();
    }
    private static void appendCanonical(StringBuilder out,JsonElement value) {
        if(value==null||value.isJsonNull()){out.append("null");return;}
        if(value.isJsonObject()) {
            out.append('{');boolean first=true;
            for(var entry:new TreeMap<>(value.getAsJsonObject().asMap()).entrySet()) {
                if(!first)out.append(',');first=false;
                out.append(new Gson().toJson(entry.getKey())).append(':');
                appendCanonical(out,entry.getValue());
            }
            out.append('}');
        } else if(value.isJsonArray()) {
            out.append('[');boolean first=true;
            for(var element:value.getAsJsonArray()) {
                if(!first)out.append(',');first=false;appendCanonical(out,element);
            }
            out.append(']');
        } else if(value.isJsonPrimitive())out.append(value.getAsJsonPrimitive().toString());
        else throw new IllegalArgumentException("Unknown JSON element");
    }
    /** Writes a raw canonical JSON file for an actual Director import call.
     * Never marks the import durable without receiving a real Director ACK.
     */
    public static String writeOnce(java.nio.file.Path batchDirectory,JsonObject g0)throws java.io.IOException {
        var dest=batchDirectory.resolve("director-g0-location-import.json");
        if(java.nio.file.Files.exists(dest,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("G0 export already exists; no overwrite");
        String body=canonical(g0);
        requireBoundedBytes(body);
        var temp=java.nio.file.Files.createTempFile(batchDirectory,".g0-", ".partial");
        try {
            try(var file=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)) {
                var bytes=java.nio.ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
                while(bytes.hasRemaining())file.write(bytes);
                file.force(true);
            }
            java.nio.file.Files.move(temp,dest,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try(var dir=java.nio.channels.FileChannel.open(batchDirectory,
                    java.nio.file.StandardOpenOption.READ)){dir.force(true);}
        } finally{java.nio.file.Files.deleteIfExists(temp);}
        return sha256(body);
    }
    static void requireBoundedBytes(String canonical)throws java.io.IOException {
        if(canonical.getBytes(StandardCharsets.UTF_8).length>1_048_576)
            throw new java.io.IOException("Director G0 export exceeds canonical UTF-8 byte maximum; no publication");
    }
    public static String sha256(String input){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
