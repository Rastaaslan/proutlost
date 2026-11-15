package fr.proutlost.worldprep.physical;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
/** Exact corrected ecology evidence. No world write and no nested session.lock.
 * Cold callers also rehash world roots/regions. Live Director callers validate
 * immutable ecological evidence only; they never hash a mutating world. */
public final class ContraPersistenceFixColdLineage {
    private static final Path B=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027");
    private static final Path R=B.resolve("contra-persistence-fix-20261030");
    private static final Path D=R.resolve("postecology");
    private static final Path W=Path.of("/home/damien/contra-ecology-run46-parallel/Contra-worldprep-modern-run46");
    private static final String UUID="873e51c5-b528-4fb7-bc34-7e0221d6c59d";
    private static final String QUAL="efb6723f35311d48527069e9d39a55766541eca13e9845a9fded36cdeeee1c13";
    private static final String ORIGINAL="c27709ad74d1175a5fc7f10c52813a998485baffd0dd41dd498b62e6f16bcc8d";
    private static final String SOURCE_MANIFEST="dd8ceec4bcb695ce4d16344d0076e23c865a70fb65362e336def3a803207caed";
    private static final String BRIDGE_PIN="e9687fcb1363a9e9364cef4aaec1082abe081550c451f63dcaaeb03bbb3fa1f9";
    private static final Map<String,String> NAMES=Map.of("ANVIL","ANVIL_FULL_65536.json","PAGED","PAGED_TWO_STORES.json","RECEIPTS","RECEIPTS_645160.json");
    private static final Map<String,String> SCRIPTS=Map.of("ANVIL","cold-audit-anvil.py","PAGED","cold-audit-paged.py","RECEIPTS","cold-audit-receipts.py");
    private static final Map<String,String> STATUSES=Map.of("ANVIL","FULL_65536_CHUNK_ANVIL_NBT_INDEPENDENT_VERIFIED_NOT_V5_CERTIFIED","PAGED","PERSISTENCE_FIX_COLD_PAGED_NATIVE_TWO_STORES_ALL_REFERENCED_PAGES_INDEPENDENT_SHA_OK_NOT_V5","RECEIPTS","CONTRA_645160_ECOLOGY_RECEIPT_SHA_CHAIN_AND_ALL_ARTIFACTS_VERIFIED_NOT_V5");
    private static void need(boolean ok,String why)throws IOException {
        if(!ok)throw new IOException("PERSISTENCE_V5_COLD_FAIL_CLOSED "+why);
    }
    private static void noLinks(Path path)throws IOException {
        for(Path node=path.toAbsolutePath().normalize();node!=null;node=node.getParent())
            need(!Files.isSymbolicLink(node),"symlink source/proof path "+node);
    }
    public static String sha(Path path)throws IOException {
        noLinks(path);
        need(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"file missing/not regular "+path);
        long links=((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue();
        need(links==1,"linked source/proof "+path);
        BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            try(InputStream stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                byte[] buf=new byte[1024*1024];int got;
                while((got=stream.read(buf))!=-1)if(got>0)d.update(buf,0,got);
            }
            BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            need(before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())
                 &&before.fileKey().equals(after.fileKey()),"file mutated during SHA "+path);
            return HexFormat.of().formatHex(d.digest());
        }catch(java.security.NoSuchAlgorithmException ex){throw new AssertionError(ex);}
    }
    private static JsonObject json(Path path)throws IOException {
        noLinks(path);
        need(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
             &&Files.size(path)>0&&Files.size(path)<8*1024*1024,
             "missing/oversized JSON proof "+path);
        try{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
        catch(RuntimeException ex){throw new IOException("invalid JSON "+path,ex);}
    }
    private static JsonObject obj(JsonObject data,String field)throws IOException {
        need(data.has(field)&&data.get(field).isJsonObject(),"missing JSON object "+field);
        return data.getAsJsonObject(field);
    }
    private static String val(JsonObject data,String field)throws IOException {
        JsonElement x=data.get(field);
        need(x!=null&&x.isJsonPrimitive(),"missing scalar "+field);
        return x.getAsString();
    }
    private static long number(JsonObject data,String field)throws IOException {
        try{return Long.parseLong(val(data,field));}
        catch(NumberFormatException ex){throw new IOException("not integer "+field,ex);}
    }
    private static boolean isTrue(JsonObject data,String field){
        return data.has(field)&&data.get(field).isJsonPrimitive()
            &&data.get(field).getAsJsonPrimitive().isBoolean()&&data.get(field).getAsBoolean();
    }
    private static boolean isFalse(JsonObject data,String field){
        return data.has(field)&&data.get(field).isJsonPrimitive()
            &&data.get(field).getAsJsonPrimitive().isBoolean()&&!data.get(field).getAsBoolean();
    }
    private static void exact(JsonObject value,String key,String expected)throws IOException {
        need(val(value,key).equals(expected),"SHA/status/world mismatch "+key);
    }

    private static void host(boolean cold)throws IOException {
        noLinks(W);need(W.toRealPath().equals(W)&&Files.isDirectory(W),"exact Contra world required");
        need(Files.getFileStore(W).getUsableSpace()>8L*1024*1024*1024,"NVMe strict >8GiB");
        need(Files.getFileStore(B).getUsableSpace()>40L*1024*1024*1024,"HDD strict >40GiB");
        if(cold)for(int port=25595;port<25600;port++){
            try(var sock=new Socket()){
                sock.connect(new InetSocketAddress("127.0.0.1",port),350);
                throw new IOException("PERSISTENCE_V5_COLD_FAIL_CLOSED reserved Minecraft port active "+port);
            }catch(ConnectException expected){}
        }
    }
    public static void verifyColdReadOnly()throws IOException { verify(true); }
    public static void verifySealedEcologyEvidenceReadOnly()throws IOException { verify(false); }
    private static void verify(boolean cold)throws IOException {
        host(cold);
        need(sha(B.resolve("CONTRA_RUN55_COLD_ADMISSION_20261028.json")).equals(ORIGINAL),"original admission changed");
        need(sha(R.resolve("QUALIFIED_COLD_CONTINUATION_SOURCE.json")).equals(QUAL),"qualified ecology source changed");
        need(sha(D.resolve("SOURCE_LINEAGE.json")).equals(SOURCE_MANIFEST),"cold auditor source manifest changed");
        need(sha(D.resolve("REGION_BRIDGE_SOURCE_PIN.json")).equals(BRIDGE_PIN),"64-region bridge source pin changed");
        need(!Files.exists(R.resolve("NATIVE_CONTINUATION_FAIL_CLOSED.json"))&&!Files.exists(R.resolve("GRACEFUL_STOP_REQUEST.json")),"corrected continuation failed/stopped");
        var auth=json(R.resolve("COLD_WINDOW_OPERATOR_AUTHORIZATION.json"));
        need(isTrue(auth,"authorized"),"explicit cold-window approval missing");
        exact(auth,"qualifiedSourceSHA256",QUAL);exact(auth,"originalBatch",UUID);exact(auth,"world",W.toString());
        exact(json(B.resolve("CONTRA_ECOLOGY_NVME_RUN118_GRACEFUL_STOP_REQUEST_20261030.json")),"operatorAuthorizationSHA256",sha(R.resolve("COLD_WINDOW_OPERATOR_AUTHORIZATION.json")));
        var ad=json(R.resolve("COLD_ADMISSION.json"));var ab=json(R.resolve("COLD_PHYSICAL_AB_SHA_VERIFIED.json"));
        var parent=json(R.resolve("COLD_PARENT_CORRESPONDENCE.json"));var start=json(R.resolve("NATIVE_CONTINUATION_STARTED.json"));
        long retained=number(ad,"savedParentCursor");
        need(retained>323566&&retained<645160&&number(ad,"verifiedSealedReceipts")==retained&&number(ab,"savedParentCursor")==retained
             &&number(parent,"savedParentCursor")==retained&&number(parent,"verifiedSealedReceipts")==retained,"exact retained cold frontier mismatch");
        exact(ad,"status","PERSISTENCE_FIX_CURRENT_COLD_SIGNED_NATIVE_ROLL_FORWARD_ADMITTED");exact(ad,"originalBatch",UUID);exact(ad,"candidateWorld",W.toString());
        exact(ad,"newABBackupProofSha256",sha(R.resolve("COLD_PHYSICAL_AB_SHA_VERIFIED.json")));
        exact(ad,"coldParentCorrespondenceSha256",sha(R.resolve("COLD_PARENT_CORRESPONDENCE.json")));
        exact(ad,"compiledProofSHA256",QUAL);
        exact(ab,"status","PERSISTENCE_FIX_INDEPENDENT_FULL_COLD_A_B_PHYSICAL_ARCHIVES_VERIFIED");
        need(isTrue(ab,"independentABDisjointInodes")&&isTrue(ab,"sourceWorldReverifiedSHA")&&number(ab,"entries")>1000000,"real independent cold A/B missing");
        exact(parent,"status","PERSISTENCE_FIX_INDEPENDENT_CURRENT_COLD_RECEIPTS_TWO_NBT_STORES_AND_DURABLE_CURSOR_SHA_VERIFIED");
        exact(start,"status","PERSISTENCE_FIX_EXACT_ONE_NATIVE_COLD_ATTEMPT");exact(start,"world",W.toString());exact(start,"batch",UUID);
        need(number(start,"coldCursor")==retained,"native retained cursor changed");
        exact(start,"coldAdmissionSHA256",sha(R.resolve("COLD_ADMISSION.json")));
        exact(start,"physicalABSHA256",sha(R.resolve("COLD_PHYSICAL_AB_SHA_VERIFIED.json")));
        var qualified=json(R.resolve("QUALIFIED_COLD_CONTINUATION_SOURCE.json"));
        exact(start,"sourceSHA256",val(obj(obj(qualified,"files"),"native-continuation/java/fr/proutlost/contra/ContraPersistenceFixColdEcologyRunner.java"),"sha256"));
        var nativeDone=json(R.resolve("NATIVE_CONTINUATION_RESULT.json"));var fin=json(R.resolve("NATIVE_CONTINUATION_FINAL.json"));
        exact(nativeDone,"status","ECOLOGY_COMPLETE_AWAITING_NEW_BACKUP_V5_CERTIFICATION");exact(nativeDone,"batch",UUID);
        need(number(nativeDone,"cursor")==645160&&isFalse(nativeDone,"V5Certified"),"genuine full ecology not complete");
        exact(fin,"status","PERSISTENCE_FIX_TRUE_645160_ECOLOGY_COMPLETE_REQUIRES_INDEPENDENT_COLD_V5_A_B");
        exact(fin,"originalBatch",UUID);exact(fin,"terminalNativeSHA256",sha(R.resolve("NATIVE_CONTINUATION_RESULT.json")));
        exact(fin,"currentColdAdmissionSHA256",sha(R.resolve("COLD_ADMISSION.json")));
        need(isFalse(fin,"V5Certified"),"ecology cannot already certify V5");
        var manifest=json(D.resolve("SOURCE_LINEAGE.json"));
        exact(manifest,"status","PERSISTENCE_FIX_INDEPENDENT_COLD_AUDITOR_SOURCES_STAGED_NOT_EXECUTED");
        exact(manifest,"driverSHA256",sha(D.resolve("cold-audit-three-driver.py")));
        exact(manifest,"originalDriverSHA256",sha(Path.of(val(manifest,"originalDriver"))));
        need(manifest.getAsJsonArray("sources").size()==3,"exactly three independent auditor sources required");
        for(var item:manifest.getAsJsonArray("sources")){
            var row=item.getAsJsonObject();Path fresh=Path.of(val(row,"new"));
            need(fresh.getParent().equals(D),"foreign auditor source");exact(row,"newSHA256",sha(fresh));
            exact(row,"originalSHA256",sha(Path.of(val(row,"original"))));
        }
        var report=json(D.resolve("THREE_COLD_AUDITS.json"));
        exact(report,"status","PERSISTENCE_FIX_ALL_THREE_COLD_INDEPENDENT_AUDITS_PASS_NOT_V5");
        exact(report,"world",W.toString());exact(report,"originalBatch",UUID);
        need(number(report,"completedStages")==645160&&isFalse(report,"physicalV5Certified")
             &&isFalse(report,"delivered")&&isFalse(report,"clientTested")&&isFalse(report,"directorComplete"),"three genuine cold audits required, no false delivery");
        exact(report,"driverSourceSHA256",sha(D.resolve("cold-audit-three-driver.py")));
        var provenance=obj(report,"terminalProvenance");exact(provenance,"originalBatch",UUID);
        need(number(provenance,"completedStages")==645160,"wrong triple-audit frontier");
        var chain=Map.ofEntries(
            Map.entry("run118NativeSHA256",R.resolve("NATIVE_CONTINUATION_RESULT.json")),
            Map.entry("run118FinalSHA256",R.resolve("NATIVE_CONTINUATION_FINAL.json")),
            Map.entry("run118ColdAdmissionSHA256",R.resolve("COLD_ADMISSION.json")),
            Map.entry("run118NativeStartSHA256",R.resolve("NATIVE_CONTINUATION_STARTED.json")),
            Map.entry("run118SourceSHA256",R.resolve("QUALIFIED_COLD_CONTINUATION_SOURCE.json")),
            Map.entry("run118GuardianPinSHA256",R.resolve("CONTINUATION_LAUNCH_PIN.json")),
            Map.entry("run118PhysicalABSHA256",R.resolve("COLD_PHYSICAL_AB_SHA_VERIFIED.json")),
            Map.entry("run118ParentProofSHA256",R.resolve("COLD_PARENT_CORRESPONDENCE.json")),
            Map.entry("run119SourceManifestSHA256",D.resolve("SOURCE_LINEAGE.json")),
            Map.entry("run103FailedClosedHistorySHA256",B.resolve("CONTRA_ECOLOGY_NVME_FAIL_CLOSED_RUN103_20261030.json")));
        for(var item:chain.entrySet())exact(provenance,item.getKey(),sha(item.getValue()));
        var audits=obj(report,"audits");need(audits.keySet().equals(NAMES.keySet()),"exactly three independent audit proofs required");
        for(String kind:NAMES.keySet()){
            Path evidence=D.resolve(NAMES.get(kind)),script=D.resolve(SCRIPTS.get(kind));
            var pin=obj(audits,kind);exact(pin,"report",evidence.toString());exact(pin,"SHA256",sha(evidence));
            exact(pin,"sourceSHA256",sha(script));exact(pin,"status",STATUSES.get(kind));
            var audit=json(evidence);exact(audit,"status",STATUSES.get(kind));exact(audit,"world",W.toString());
            if(kind.equals("ANVIL"))need(number(audit,"fullLitModernChunks")==65536&&isFalse(audit,"physicalV5Certified"),"65536 FULL Anvil proof missing");
            else if(kind.equals("PAGED")){
                need(isFalse(audit,"V5Certified")&&audit.getAsJsonArray("stores").size()==2,"two paged stores not audited");
                var names=new HashSet<String>();
                for(var item:audit.getAsJsonArray("stores")){
                    var row=item.getAsJsonObject();String name=val(row,"store");
                    need(Set.of("proutlost_worldprep","proutlost_worldprep_blocks").contains(name)&&names.add(name)
                         &&number(row,"referencedPages")>0,"invalid paged store coverage");
                    if(cold)exact(row,"rootSha256",sha(W.resolve("data/"+name+".dat")));
                }
            }else{
                need(number(audit,"stages")==645160&&isTrue(audit,"readOnlyIndependent")
                     &&isTrue(audit,"noSkippingOrFabricatedStages")&&isFalse(audit,"V5Qualified"),"all 645160 receipts not independently proven");
                exact(audit,"ecologyBatch",UUID);Path batch=W.resolve("worldprep-v2/batches/"+UUID);
                exact(audit,"manifestSha256",sha(batch.resolve("manifest.json")));
                exact(audit,"terminalReceiptSha256",sha(batch.resolve("00000000000000645159.json")));
            }
        }
        var bridgeSource=json(D.resolve("REGION_BRIDGE_SOURCE_PIN.json"));
        exact(bridgeSource,"bridgeSourceSHA256",sha(D.resolve("cold-bridge-regions.py")));
        exact(bridgeSource,"run119DriverSHA256",sha(D.resolve("cold-audit-three-driver.py")));
        var region=json(D.resolve("ORIGINAL_64_REGIONS_PIN.json"));
        exact(region,"status","RUN120_POSTRUN118_64_ORIGINAL_ANVIL_REGIONS_COLD_SHA_FULL_PIN");
        exact(region,"world",W.toString());exact(region,"originalBatch",UUID);
        exact(region,"run119TripleReportSHA256",sha(D.resolve("THREE_COLD_AUDITS.json")));
        exact(region,"run120SourceSHA256",sha(D.resolve("cold-bridge-regions.py")));
        need(number(region,"regionCount")==64&&number(region,"completedEcologyStages")==645160&&isFalse(region,"V5Certified"),"exact original region pin required");
        var names=new HashSet<String>();
        for(var item:region.getAsJsonArray("regions")){
            var row=item.getAsJsonObject();String name=val(row,"name");
            need(name.matches("r\\.[0-7]\\.[0-7]\\.mca")&&names.add(name)&&number(row,"bytes")>=8192,"duplicate/foreign region pin");
            if(cold){Path p=W.resolve("region/"+name);need(Files.size(p)==number(row,"bytes"),"region bytes changed");exact(row,"sha256",sha(p));}
        }
        need(names.size()==64,"all 64 original regions required");
    }
    public static void main(String[] args)throws Exception {
        if(!Arrays.equals(args,new String[]{"--inspect"}))throw new IllegalArgumentException("Read-only --inspect only");
        verifyColdReadOnly();System.out.println("PERSISTENCE_FIX_GENUINE_COLD_ECOLOGY_LINEAGE_VERIFIED_NOT_V5");
    }
    private ContraPersistenceFixColdLineage(){}
}
