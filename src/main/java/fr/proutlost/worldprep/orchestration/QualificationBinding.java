package fr.proutlost.worldprep.orchestration;

import com.google.gson.*;
import com.google.gson.stream.*;
import fr.proutlost.worldprep.physical.PhysicalStore;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import fr.proutlost.worldprep.physical.NativeV5QualificationIntent;
import fr.proutlost.worldprep.physical.ContraV5SectorLineage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded strict intent -> immutable two-header binding -> exact runtime bytes.
 * Caller freezes intent before the save/copy boundary. No arbitrary exclusions.
 */
public final class QualificationBinding {
    public static final Gson JSON=new GsonBuilder().serializeNulls().create();
    public static final String REQUEST="worldprep-qualification.json";
    public record Binding(int version,String intentSha256,String request,String requestSha256,String firstSha256,String secondSha256){}
    /** RUN69, separate immutable multi-segment V5 cold audit and runtime WAL. */
    public record SegmentedBinding(int version,String intentSha256,String request,
        String requestSha256,String manifestSha256,String coldAuditSha256,
        String pinSha256,String inventorySha256,long verifiedFiles,long verifiedPages,
        int verifiedSegments) {}
    private final Path world,directory;
    private final PhysicalStore store;
    private final Class<?> shape;
    public QualificationBinding(Path world,Path directory,Class<?> shape)throws IOException {
        this.world=world.toAbsolutePath().normalize();this.directory=directory.toAbsolutePath().normalize();this.shape=shape;
        noLinks(this.world);noLinks(this.directory);
        if(!this.directory.startsWith(this.world.resolve("worldprep-v2/physical")))throw new IOException("Qualification journal outside physical root");
        store=new PhysicalStore(this.directory.resolve("configuration"));
    }
    public Path first(){return directory.resolve("backup-a.json");}
    public Path second(){return directory.resolve("backup-b.json");}
    public Set<Path> exclusions(){return Set.of(world.relativize(directory),Path.of(REQUEST));}
    public static String read(Path path,int max)throws IOException {
        noLinks(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>max)throw new IOException("Missing/oversized protocol input: "+path);
        try(var in=Files.newInputStream(path)){byte[] b=in.readNBytes(max+1);if(b.length>max)throw new IOException("Growing input");return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}
    }
    private static void noLinks(Path p)throws IOException {for(;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink protocol path");}
    public String freeze(String semantic)throws IOException {
        JsonObject object=object(semantic);
        if(object.has("backupSha256"))throw new IOException("Intent must precede derived backup hashes");
        object.addProperty("backupSha256",PagedBackupVerifier.ZERO);validate(object,shape);
        Object typed=decode(canonical(object),shape);
        if(!canonical(JSON.toJsonTree(typed)).equals(canonical(object)))throw new IOException("Semantic input must use exact typed canonical representation");
        int version=object.get("version").getAsInt();if(version!=3&&version!=4&&version!=5)throw new IOException("LEGACY_REQUEST_REQUIRES_NEW_PROTOCOL_NO_RESET");
        if(version==5&&exactContraSegmented(decode(canonical(object),NativeV5QualificationIntent.class))) {
            // RUN69: 645160-stage Contra needs external segmented immutable A/B.
            // This is NOT an arbitrary backup path: both roots dictate the sole
            // canonical manifest location, and old V2 paths remain unchanged.
        } else if(!object.get("backupManifest").getAsString().equals(world.relativize(first()).toString()))
            throw new IOException("Fixed backup path required");
        UUID batch=UUID.fromString(object.get("batch").getAsString());if(!directory.getFileName().toString().equals("qualification-"+batch))throw new IOException("Batch journal mismatch");
        Path rootA=Path.of(object.get("backupRootA").getAsString()),rootB=Path.of(object.get("backupRootB").getAsString());
        for(var root:List.of(rootA,rootB)){if(!root.isAbsolute()||!root.normalize().equals(root)||root.startsWith(world)||world.startsWith(root))throw new IOException("Canonical independent backup roots required");noLinks(root);}
        if(rootA.startsWith(rootB)||rootB.startsWith(rootA))throw new IOException("Distinct planned backups required");
        object.remove("backupSha256");String canonical=canonical(object);
        store.write("intent",canonical);return PhysicalStore.sha(canonical);
    }
    public String intent()throws IOException{return store.read("intent",String.class);}
    public boolean bound(){return store.exists("binding");}
    public boolean segmentedBound(){return store.exists("segmented-binding");}

    /** This route is exclusive to the pinned 645160-stage global Contra identity.
     * It cannot be used for a normal V3/V4 or a legacy V5 frozen V2 backup.
     */
    public static boolean exactContraSegmented(NativeV5QualificationIntent req) {
        if(req==null||req.version()!=5||req.ecology()==null
            ||req.ecology().stages()!=645160
            ||!ContraV5SectorLineage.PARENT.equals(req.area())
            ||!"minecraft:overworld".equals(req.ecology().dimension())
            ||req.ecology().worldSeed()!=ContraV5SectorLineage.SEED
            ||!req.ecology().batchId().toString().equals("873e51c5-b528-4fb7-bc34-7e0221d6c59d"))
            return false;
        try {
            Path a=Path.of(req.backupRootA()),b=Path.of(req.backupRootB());
            if(!a.isAbsolute()||!b.isAbsolute()||!a.normalize().equals(a)
                ||!b.normalize().equals(b)||!a.getFileName().toString().equals("copy-A")
                ||!b.getFileName().toString().equals("copy-B")
                ||!a.getParent().equals(b.getParent()))return false;
            return Path.of(req.backupManifest()).equals(
                a.getParent().resolve("manifest-bundle/manifest.json"));
        }catch(RuntimeException e){return false;}
    }
    private static String digestBytes(Path f,int max)throws IOException {
        noLinks(f);
        if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||Files.size(f)>max)
            throw new IOException("Missing/oversized pinned cold audit evidence");
        try{
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    /** On actual Contra, bind frozen V5 to exact RUN103/104/106 proof, never
     * claim the historical RUN92 FAILED_BOOT as successful ecology.
     * Called under a held native session.lock during final A/B SHA: MUST NOT
     * call any helper that re-opens/closes session.lock.
     */
    private void requireCurrentColdPin(ContraV5SegmentedBackupAudit.Manifest m,
                                       String frozenIntent)throws IOException {
        if(!world.equals(Path.of("/home/damien/contra-ecology-run46-parallel/Contra-worldprep-modern-run46")))return;
        fr.proutlost.worldprep.physical.ContraPersistenceFixColdLineage.verifyColdReadOnly();
        Path report=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/contra-persistence-fix-20261030/postecology/THREE_COLD_AUDITS.json");
        Path region=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/contra-persistence-fix-20261030/postecology/ORIGINAL_64_REGIONS_PIN.json");
        JsonObject pin=object(read(world.resolve(m.pinPath()),4096));
        if(!pin.has("originalEcologyBatch")||!pin.get("originalEcologyBatch").getAsString().equals("873e51c5-b528-4fb7-bc34-7e0221d6c59d")
            ||!pin.has("nativeIntentSha256")||!pin.get("nativeIntentSha256").getAsString().equals(PhysicalStore.sha(frozenIntent))
            ||!pin.has("run119ReportSha256")||!pin.get("run119ReportSha256").getAsString().equals(digestBytes(report,2*1024*1024))
            ||!pin.has("run120RegionPinSha256")||!pin.get("run120RegionPinSha256").getAsString().equals(digestBytes(region,2*1024*1024)))
            throw new IOException("Corrected ecology exact cold audit/region/native-intent pins not preserved");
    }
    /** WAL publication after both real headers/pages exist; never updates an existing binding. */
    public Binding seal()throws IOException {
        String intent=intent();if(!canonical(object(intent)).equals(intent))throw new IOException("Noncanonical intent");
        int protocol=object(intent).get("version").getAsInt();
        if(protocol==5&&shape==NativeV5QualificationIntent.class) {
            JsonObject probe=object(intent);probe.addProperty("backupSha256",PagedBackupVerifier.ZERO);
            if(exactContraSegmented(decode(canonical(probe),NativeV5QualificationIntent.class)))
                throw new IOException("Contra 645160 global V5 requires segmented cold seal, not old V2");
        }
        if(protocol==5&&shape!=fr.proutlost.worldprep.physical.NativeV5QualificationIntent.class)
            throw new IOException("V5 sealed binding must be typed to the exact v5 native request");
        String a=read(first(),65536),b=read(second(),65536);
        var ha=decode(a,PagedBackupVerifier.Header.class);var hb=decode(b,PagedBackupVerifier.Header.class);
        if(ha.version()!=2||hb.version()!=2||!ha.sourceRoot().equals(world.toString())||!hb.sourceRoot().equals(world.toString()))throw new IOException("V2 source-bound headers required");
        var planned=object(intent);
        if(!ha.backupRoot().equals(planned.get("backupRootA").getAsString())||!hb.backupRoot().equals(planned.get("backupRootB").getAsString()))throw new IOException("Backup roots differ from pre-copy intent");
        Path ba=Path.of(ha.backupRoot()).toRealPath(),bb=Path.of(hb.backupRoot()).toRealPath();noLinks(Path.of(ha.backupRoot()));noLinks(Path.of(hb.backupRoot()));
        if(ba.startsWith(bb)||bb.startsWith(ba)||ba.startsWith(world)||world.startsWith(ba)||bb.startsWith(world)||world.startsWith(bb))throw new IOException("Two independent backups required");
        if(!ha.quiescentPinPath().equals(hb.quiescentPinPath())||!ha.quiescentPinSha256().equals(hb.quiescentPinSha256())||ha.files()!=hb.files())throw new IOException("Backup generation mismatch");
        var request=object(intent);request.addProperty("backupSha256",PhysicalStore.sha(a));validate(request,shape);
        String text=canonical(request);
        if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)
            throw new IOException("Runtime request bound");
        if(protocol==5) {
            // V5's actual multi-species engine and nine-phase native session are
            // separate from V3/V4; never seal from an incompatible legacy shape.
            var typed=decode(text,fr.proutlost.worldprep.physical.NativeV5QualificationIntent.class);
            fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan.verify(typed.multiSpecies());
            if(typed.version()!=5||!typed.enabled()
                ||!typed.batch().equals(typed.multiSpecies().parentBatch())
                ||!typed.backupSha256().equals(PhysicalStore.sha(a))
                ||typed.ecology().stages()<1)
                throw new IOException("Native v5 sealed species/world/backup identity rejected");
        }
        var binding=new Binding(1,PhysicalStore.sha(intent),text,PhysicalStore.sha(text),PhysicalStore.sha(a),PhysicalStore.sha(b));
        store.write("binding",canonical(JSON.toJsonTree(binding))); // forced immutable WAL BEFORE request publication
        publishRequest(binding);verify();return binding;
    }
    public void publishRequest(Binding binding)throws IOException {
        Binding retained=decode(store.read("binding",String.class),Binding.class);
        if(!retained.equals(binding))throw new IOException("Binding not archived");
        Path target=world.resolve(REQUEST);noLinks(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!read(target,4096).equals(binding.request()))throw new IOException("Existing runtime request differs; no overwrite");return;}
        Path temp=Files.createTempFile(world,".qualification-request-",".partial");
        try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){var bytes=ByteBuffer.wrap(binding.request().getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        try(var dir=FileChannel.open(world,StandardOpenOption.READ)){dir.force(true);}
    }
    public Binding verify()throws IOException {
        Binding binding=decode(store.read("binding",String.class),Binding.class);String intent=intent();
        if(binding.version()!=1||!PhysicalStore.sha(intent).equals(binding.intentSha256())||!PhysicalStore.sha(binding.request()).equals(binding.requestSha256()))throw new IOException("Intent/binding checksum mismatch");
        var request=object(binding.request());validate(request,shape);
        if(!canonical(request).equals(binding.request())||!request.get("backupSha256").getAsString().equals(binding.firstSha256()))throw new IOException("Noncanonical request/binding");
        request.remove("backupSha256");if(!canonical(request).equals(intent))throw new IOException("Semantic substitution");
        if(!read(world.resolve(REQUEST),4096).equals(binding.request()))throw new IOException("Runtime request bytes changed");
        if(!PhysicalStore.sha(read(first(),65536)).equals(binding.firstSha256())||!PhysicalStore.sha(read(second(),65536)).equals(binding.secondSha256()))throw new IOException("Backup header changed");
        return binding;
    }
    /**
     * RUN69: one-time native V5 COLD multi-segment seal.
     * The SHA of the entire manifest, independent full A/B/source file-byte
     * verification, pinned RUN104/RUN106 and exact WAL are durable BEFORE runtime
     * publication. No legacy V2 limits, no inactive world, no unsigned claims.
     */
    public SegmentedBinding sealSegmented()throws IOException {
        if(shape!=NativeV5QualificationIntent.class||bound()||segmentedBound()
            ||Files.exists(world.resolve(REQUEST),LinkOption.NOFOLLOW_LINKS))
            throw new IOException("RUN69 must exclusively seal a new native V5 WAL");
        String semantic=intent();
        if(!canonical(object(semantic)).equals(semantic))
            throw new IOException("Frozen pre-copy intent was not canonical");
        JsonObject proposed=object(semantic);
        proposed.addProperty("backupSha256",PagedBackupVerifier.ZERO);
        NativeV5QualificationIntent typed=decode(canonical(proposed),NativeV5QualificationIntent.class);
        if(!exactContraSegmented(typed)
            ||!directory.getFileName().toString().equals("qualification-"+typed.batch()))
            throw new IOException("RUN69 only the exact 645160-stage Contra V5 batch");
        Path manifest=Path.of(typed.backupManifest());
        noLinks(manifest);
        String raw=read(manifest,65536);
        var parsed=decode(raw,ContraV5SegmentedBackupAudit.Manifest.class);
        if(!parsed.qualification().equals(typed.batch().toString())
            ||!parsed.world().equals(world.toString())
            ||!parsed.backupA().equals(typed.backupRootA())
            ||!parsed.backupB().equals(typed.backupRootB()))
            throw new IOException("RUN69 source/UUID/A/B identity mismatch before cold audit");
        requireCurrentColdPin(parsed,semantic);
        Path sourceLock=world.resolve("session.lock");noLinks(sourceLock);
        if(!Files.isRegularFile(sourceLock,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native cold session.lock missing");
        try(var channel=FileChannel.open(sourceLock,StandardOpenOption.READ,StandardOpenOption.WRITE)){
            try(FileLock held=channel.tryLock()){
                if(held==null)throw new IOException("Minecraft currently holds Contra session.lock");
                var cold=ContraV5SegmentedBackupAudit.verifyWithHeldColdLock(
                    manifest,world,Path.of(typed.backupRootA()),Path.of(typed.backupRootB()),
                    typed.batch(),held);
                if(!"COLD_SEGMENTED_A_B_CONTENT_AND_FULL_COVERAGE_SHA_VERIFIED_NOT_V5_QUALIFIED"
                    .equals(cold.status())
                    ||!cold.headerManifestSha256().equals(PhysicalStore.sha(raw))
                    ||!cold.inventorySha256().equals(parsed.inventorySha256())
                    ||cold.files()!=parsed.totalFiles()||cold.pages()!=parsed.totalPages()
                    ||cold.segments()!=parsed.segments().size())
                    throw new IOException("RUN69 full cold audit did not verify exact segment bundle");
                // Must repeat identity after the long cold readback, with lock still held.
                if(!PhysicalStore.sha(read(manifest,65536)).equals(cold.headerManifestSha256()))
                    throw new IOException("RUN69 manifest changed during cold audit");
                requireCurrentColdPin(parsed,semantic);
                JsonObject assembled=object(semantic);
                assembled.addProperty("backupSha256",cold.headerManifestSha256());
                String runtime=canonical(assembled);
                decode(runtime,NativeV5QualificationIntent.class);
                if(runtime.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)
                    throw new IOException("RUN69 runtime request exceeds native bound");
                String auditDigest=store.write("segmented-cold-verified",cold);
                var sealed=new SegmentedBinding(3,PhysicalStore.sha(semantic),runtime,
                    PhysicalStore.sha(runtime),cold.headerManifestSha256(),auditDigest,
                    parsed.pinSha256(),cold.inventorySha256(),cold.files(),cold.pages(),cold.segments());
                store.write("segmented-binding",sealed); // immutable WAL BEFORE publish
                publishSegmentedRequest(sealed);
                verifySegmented();
                return sealed;
            }
        }catch(OverlappingFileLockException e){
            throw new IOException("RUN69 refused live Minecraft source session",e);
        }
    }
    private void publishSegmentedRequest(SegmentedBinding expected)throws IOException {
        if(!store.read("segmented-binding",SegmentedBinding.class).equals(expected))
            throw new IOException("RUN69 missing exact durable segmented WAL");
        Path target=world.resolve(REQUEST);noLinks(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("RUN69 runtime V5 request was already published");
        Path temp=Files.createTempFile(world,".segmented-qualification-request-",".partial");
        try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
            var bytes=ByteBuffer.wrap(expected.request().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        try(var dir=FileChannel.open(world,StandardOpenOption.READ)){dir.force(true);}
    }
    /** Read-only WAL recheck safe after Minecraft starts: no running world source
     * SHA assumption. Actual full contents were independently checked COLD.
     * The separate RUN68 page cursor also checks every segment and pin.
     */
    public SegmentedBinding verifySegmented()throws IOException {
        if(bound()||!segmentedBound()||shape!=NativeV5QualificationIntent.class)
            throw new IOException("No exclusively sealed RUN69 multi-segment V5 WAL");
        SegmentedBinding sealed=store.read("segmented-binding",SegmentedBinding.class);
        String semantic=intent();
        if(sealed.version()!=3||!PhysicalStore.sha(semantic).equals(sealed.intentSha256())
            ||!PhysicalStore.sha(sealed.request()).equals(sealed.requestSha256())
            ||!sealed.request().equals(read(world.resolve(REQUEST),4096)))
            throw new IOException("RUN69 immutable runtime V5 request or intent mismatch");
        var proposed=object(sealed.request());
        NativeV5QualificationIntent typed=decode(sealed.request(),NativeV5QualificationIntent.class);
        if(!canonical(proposed).equals(sealed.request())||!exactContraSegmented(typed)
            ||!sealed.manifestSha256().equals(typed.backupSha256()))
            throw new IOException("RUN69 source/format/UUID runtime V5 substitution");
        proposed.remove("backupSha256");
        if(!canonical(proposed).equals(semantic))
            throw new IOException("RUN69 semantic identity differs from frozen intent");
        Path manifest=Path.of(typed.backupManifest());noLinks(manifest);
        String raw=read(manifest,65536);
        if(!PhysicalStore.sha(raw).equals(sealed.manifestSha256()))
            throw new IOException("RUN69 segmented manifest content SHA changed");
        var parsed=decode(raw,ContraV5SegmentedBackupAudit.Manifest.class);
        if(parsed.version()!=3||!parsed.world().equals(world.toString())
            ||!parsed.qualification().equals(typed.batch().toString())
            ||!parsed.backupA().equals(typed.backupRootA())
            ||!parsed.backupB().equals(typed.backupRootB())
            ||!parsed.pinSha256().equals(sealed.pinSha256())
            ||!parsed.inventorySha256().equals(sealed.inventorySha256())
            ||parsed.totalFiles()!=sealed.verifiedFiles()
            ||parsed.totalPages()!=sealed.verifiedPages()
            ||parsed.segments().size()!=sealed.verifiedSegments())
            throw new IOException("RUN69 immutable manifest/WAL/source identity mismatch");
        var audit=store.read("segmented-cold-verified",ContraV5SegmentedBackupAudit.Verified.class);
        if(!PhysicalStore.sha(store.readText("segmented-cold-verified")).equals(sealed.coldAuditSha256())
            ||!audit.status().equals("COLD_SEGMENTED_A_B_CONTENT_AND_FULL_COVERAGE_SHA_VERIFIED_NOT_V5_QUALIFIED")
            ||!audit.headerManifestSha256().equals(sealed.manifestSha256())
            ||!audit.inventorySha256().equals(sealed.inventorySha256())
            ||audit.files()!=sealed.verifiedFiles()
            ||audit.pages()!=sealed.verifiedPages()
            ||audit.segments()!=sealed.verifiedSegments())
            throw new IOException("RUN69 independent cold A/B readback receipt not pinned");
        for(Path root:List.of(world,Path.of(typed.backupRootA()),Path.of(typed.backupRootB()))) {
            noLinks(root);
            if(!digestBytes(root.resolve(parsed.pinPath()),4096).equals(sealed.pinSha256()))
                throw new IOException("RUN69 generation pin changed in A/B/source");
        }
        return sealed;
    }
    public ContraV5SegmentedBackupAudit.Verified segmentedColdAudit()throws IOException {
        verifySegmented();
        return store.read("segmented-cold-verified",ContraV5SegmentedBackupAudit.Verified.class);
    }
    public static JsonObject object(String text)throws IOException{var e=parse(text);if(!e.isJsonObject())throw new IOException("Object required");return e.getAsJsonObject();}
    public static <T>T decode(String text,Class<T> type)throws IOException{var e=parse(text);validate(e,type);try{return JSON.fromJson(e,type);}catch(RuntimeException ex){throw new IOException("Invalid typed input",ex);}}
    public static JsonElement parse(String text)throws IOException {
        if(text.length()>131072)throw new IOException("JSON bound");
        try(var r=new JsonReader(new StringReader(text))){r.setLenient(false);var e=value(r,0);if(r.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Trailing JSON");return e;}
        catch(RuntimeException e){throw new IOException("Malformed JSON",e);}
    }
    private static JsonElement value(JsonReader r,int depth)throws IOException {
        if(depth>16)throw new IOException("JSON depth");
        return switch(r.peek()){
            case BEGIN_OBJECT->{var o=new JsonObject();r.beginObject();int count=0;while(r.hasNext()){String k=r.nextName();if(++count>128||o.has(k))throw new IOException("Duplicate/oversized object");o.add(k,value(r,depth+1));}r.endObject();yield o;}
            case BEGIN_ARRAY->{var a=new JsonArray();r.beginArray();while(r.hasNext()){if(a.size()==256)throw new IOException("Array bound");a.add(value(r,depth+1));}r.endArray();yield a;}
            case STRING->new JsonPrimitive(r.nextString());case BOOLEAN->new JsonPrimitive(r.nextBoolean());
            case NUMBER->{String n=r.nextString();if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IOException("Invalid number");yield new JsonPrimitive(new java.math.BigDecimal(n));}
            case NULL->{r.nextNull();yield JsonNull.INSTANCE;}default->throw new IOException("Invalid JSON token");};
    }
    private static void validate(JsonElement e,Type type)throws IOException {
        if(e.isJsonNull()){if(type instanceof Class<?> c&&c.isPrimitive())throw new IOException("Null primitive");return;}
        if(type instanceof ParameterizedType p&&p.getRawType() instanceof Class<?> c&&Collection.class.isAssignableFrom(c)){
            if(!e.isJsonArray())throw new IOException("Array required");var seen=new HashSet<String>();for(var item:e.getAsJsonArray()){validate(item,p.getActualTypeArguments()[0]);if(Set.class.isAssignableFrom(c)&&!seen.add(canonical(item)))throw new IOException("Duplicate set member");}return;
        }
        if(!(type instanceof Class<?> c))throw new IOException("Unsupported type");
        if(c.isRecord()){
            if(!e.isJsonObject())throw new IOException("Record required");var o=e.getAsJsonObject();var names=new HashSet<String>();for(var component:c.getRecordComponents()){names.add(component.getName());if(!o.has(component.getName()))throw new IOException("Missing field "+component.getName());validate(o.get(component.getName()),component.getGenericType());}
            if(!o.keySet().equals(names))throw new IOException("Unknown fields");return;
        }
        if(!e.isJsonPrimitive())throw new IOException("Scalar required");var p=e.getAsJsonPrimitive();
        try{
            if(c==String.class){if(!p.isString())throw new IOException("String required");}
            else if(c==UUID.class){if(!p.isString()||!UUID.fromString(p.getAsString()).toString().equals(p.getAsString()))throw new IOException("UUID required");}
            else if(c==boolean.class||c==Boolean.class){if(!p.isBoolean())throw new IOException("Boolean required");}
            else if(c==int.class||c==long.class){if(!p.isNumber())throw new IOException("Integer required");if(c==int.class)p.getAsBigDecimal().intValueExact();else p.getAsBigDecimal().longValueExact();}
            else if(c==double.class||c==float.class){if(!p.isNumber()||!Double.isFinite(p.getAsDouble()))throw new IOException("Finite number required");}
            else throw new IOException("Unsupported scalar "+c);
        }catch(RuntimeException ex){throw new IOException("Invalid scalar",ex);}
    }
    public static String canonical(JsonElement e){
        if(e.isJsonObject()){var sorted=new JsonObject();e.getAsJsonObject().keySet().stream().sorted().forEach(k->sorted.add(k,JsonParser.parseString(canonical(e.getAsJsonObject().get(k)))));return JSON.toJson(sorted);}
        if(e.isJsonArray()){var a=new JsonArray();for(var i:e.getAsJsonArray())a.add(JsonParser.parseString(canonical(i)));return JSON.toJson(a);}return JSON.toJson(e);
    }
}
