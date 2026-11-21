package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit synthetic metadata fixture writer; no Minecraft/J04 writer or physical proof.
 * Use only on a newly assigned isolated TEST_ONLY QA world. Never overwrites files.
 */
public final class NativePagedLocationQaInput {
    private NativePagedLocationQaInput(){}
    private static UUID id(WorldId world,ResourceId source,String kind,int index){return UUID.nameUUIDFromBytes(
        ("P008-declared-synthetic-g0/"+world.value()+"/"+source.value()+"/"+kind+"/"+index).getBytes(StandardCharsets.UTF_8));}
    public static LocationImport source(WorldId world,ResourceId source){
        if(!source.value().startsWith("qa:"))throw new IllegalArgumentException("explicit qa namespace required");
        var dimension=new DimensionId("minecraft:overworld");
        List<ImportedRegion> regions=new ArrayList<>();List<ImportedLocation> locations=new ArrayList<>();List<ImportedAnchor> anchors=new ArrayList<>();
        for(int n=0;n<80;n++){
            int x=1_000_000+n*1024;
            regions.add(new ImportedRegion(new RegionId(id(world,source,"region",n)),dimension,
                new ImportBounds(x,50,1_000_000,x+64,80,1_000_064),Set.of(new TagId("proutlost:clearing"))));
        }
        for(int n=0;n<600;n++){
            int r=n%80,slot=n/80,x=1_000_000+r*1024+2+slot*7,z=1_000_002;
            var location=new LocationId(id(world,source,"location",n));
            locations.add(new ImportedLocation(location,"declared-qa-location-"+n,Set.of(regions.get(r).regionId()),dimension,
                new ImportBounds(x,60,z,x+5,70,z+5),Set.of(new TagId("proutlost:structure"))));
            anchors.add(new ImportedAnchor(new AnchorId(id(world,source,"anchor",n)),location,dimension,
                new ImportPosition(x+2,64,z+2),Set.of(new TagId("proutlost:anchor_center"))));
        }
        var initial=new LocationImport(1,world,source,new Revision(1),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        return new LocationImport(1,world,source,initial.exportRevision(),StandaloneLocationImporter.digest(initial),regions,locations,anchors);
    }
    /** Same declared QA identities; a new revision, never historical data. */
    public static LocationImport revised(LocationImport original,long revision,int firstRegionMaxXDelta){
        if(!original.sourceNamespace().value().startsWith("qa:")||original.regions().size()!=80
            ||original.locations().size()!=600||original.anchors().size()!=600||firstRegionMaxXDelta<0||firstRegionMaxXDelta>4)
            throw new IllegalArgumentException("declared 80/600/600 QA revision only");
        var regions=new ArrayList<>(original.regions());var first=regions.getFirst();var b=first.bounds();
        regions.set(0,new ImportedRegion(first.regionId(),first.dimension(),new ImportBounds(b.minX(),b.minY(),b.minZ(),
            Math.addExact(b.maxXExclusive(),firstRegionMaxXDelta),b.maxYExclusive(),b.maxZExclusive()),first.tags()));
        var draft=new LocationImport(original.schemaVersion(),original.worldId(),original.sourceNamespace(),new Revision(revision),
            new Fingerprint("0".repeat(64)),regions,original.locations(),original.anchors());
        return new LocationImport(draft.schemaVersion(),draft.worldId(),draft.sourceNamespace(),draft.exportRevision(),
            StandaloneLocationImporter.digest(draft),draft.regions(),draft.locations(),draft.anchors());
    }
    public static void main(String[] args)throws Exception{
        if(args.length<4||args.length>5)throw new IllegalArgumentException("QA_WORLD_ROOT WORLD_UUID qa:SOURCE NAME_transfer [revision2|negative-cases]");
        Path worldRoot=Path.of(args[0]).toRealPath();
        for(Path p=worldRoot;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("symlink QA root refused");
        Path marker=worldRoot.resolve("TEST_ONLY");
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>128
            ||!Files.readString(marker,StandardCharsets.UTF_8).trim().equals("PROUTLOST_NEUTRAL_QA_V1"))
            throw new IllegalArgumentException("exact PROUTLOST_NEUTRAL_QA_V1 TEST_ONLY world marker required");
        String name=args[3];if(!name.matches("[a-z0-9][a-z0-9_-]{0,54}_transfer"))throw new IllegalArgumentException("owned transfer fixture name");
        var input=source(new WorldId(UUID.fromString(args[1])),new ResourceId(args[2]));
        if(args.length==5){
            Path original=worldRoot.resolve("director-location-imports/p008_qa_transfer-expected.json");
            if(Files.isSymbolicLink(original)||!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)||Files.size(original)>LocationTransfer.SOURCE_BYTES)
                throw new IllegalArgumentException("preserved original R1 QA source required");
            byte[] prior=Files.readAllBytes(original);var codec=new ModelCodec<>(LocationImport.class);
            if(!Arrays.equals(prior,codec.encode(input)))throw new IllegalArgumentException("preserved R1 differs from exact declared source/world/identities");
            if(args[4].equals("negative-cases")){negativeCases(worldRoot,input);return;}
            if(!args[4].equals("revision2"))throw new IllegalArgumentException("explicit revision2 or negative-cases mode required");
            input=revised(input,2,1);
        }
        writeTransfer(worldRoot,name,input,"");
    }
    private static void writeTransfer(Path worldRoot,String name,LocationImport input,String mutation)throws Exception{
        var transfer=LocationTransfer.prepare(input);if(transfer.pages().size()<2)throw new AssertionError("multi-page QA source required");
        Path inputs=worldRoot.resolve("director-location-imports"),directory=inputs.resolve(name);
        if(Files.isSymbolicLink(inputs))throw new IllegalArgumentException("symlink QA input directory refused");
        Files.createDirectories(inputs);Files.createDirectory(directory);
        byte[] expected=new ModelCodec<>(LocationImport.class).encode(input);
        Files.write(inputs.resolve(name+"-expected.json"),expected,StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("manifest.json"),new ModelCodec<>(LocationTransferManifest.class).encode(transfer.manifest()),StandardOpenOption.CREATE_NEW);
        // Delivery order deliberately differs from page index; an identical
        // duplicate remains a transport replay, never a second source revision.
        for(int i=transfer.pages().size()-1;i>=0;i--){
            if(mutation.equals("missing")&&i==0)continue;
            byte[] wire=new ModelCodec<>(LocationTransferPage.class).encode(transfer.pages().get(i));
            if(mutation.equals("corruption")&&i==1)wire=contradictory(transfer.pages().get(i));
            if(mutation.equals("oversize")&&i==1){wire=new byte[LocationTransfer.WIRE_BYTES+1];Arrays.fill(wire,(byte)'A');}
            Files.write(directory.resolve(String.format(Locale.ROOT,"message-%03d.json",i)),wire,StandardOpenOption.CREATE_NEW);
        }
        if(!mutation.equals("missing"))Files.write(directory.resolve("duplicate-000.json"),mutation.equals("contradictory_duplicate")
            ?contradictory(transfer.pages().getFirst()):new ModelCodec<>(LocationTransferPage.class).encode(transfer.pages().getFirst()),StandardOpenOption.CREATE_NEW);
        var first=input.anchors().getFirst();var far=input.anchors().get(79);
        System.out.println("P008_DECLARED_SYNTHETIC_G0_FIXTURE world="+input.worldId().value()+" source="+input.sourceNamespace().value()
            +" source_sha256="+BlobContent.hash(expected).value()+" manifest_sha256="+LocationTransfer.identity(transfer.manifest()).value()
            +" revision="+input.exportRevision().value()+" fixture="+name+" mutation="+(mutation.isEmpty()?"none":mutation)
            +" pages="+transfer.pages().size()+" regions=80 locations=600 anchors=600 expected_heads=1360"
            +" first_anchor="+first.anchorId().value()+" first_position="+first.position().x()+","+first.position().y()+","+first.position().z()
            +" far_anchor="+far.anchorId().value()+" far_position="+far.position().x()+","+far.position().y()+","+far.position().z());
    }
    private static byte[] contradictory(LocationTransferPage page){
        byte[] fragment=Base64.getDecoder().decode(page.bytesBase64());fragment[0]^=1;
        return new ModelCodec<>(LocationTransferPage.class).encode(new LocationTransferPage(page.manifestSha256(),page.pageIndex(),Base64.getEncoder().encodeToString(fragment)));
    }
    private static void negativeCases(Path root,LocationImport r1)throws Exception{
        var r2=revised(r1,2,1);
        for(String name:List.of("p008_bad_missing_transfer","p008_bad_duplicate_transfer","p008_bad_corrupt_transfer","p008_bad_oversize_transfer",
            "p008_bad_conflict_r1_transfer","p008_bad_conflict_r2_transfer","p008_bad_stale_r1_transfer","p008_qa_r1_replay_transfer")){
            Path inputs=root.resolve("director-location-imports");
            if(Files.exists(inputs.resolve(name),LinkOption.NOFOLLOW_LINKS)||Files.exists(inputs.resolve(name+"-expected.json"),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("preserve existing QA negative-case evidence: "+name);
        }
        writeTransfer(root,"p008_bad_missing_transfer",r2,"missing");
        writeTransfer(root,"p008_bad_duplicate_transfer",r2,"contradictory_duplicate");
        writeTransfer(root,"p008_bad_corrupt_transfer",r2,"corruption");
        writeTransfer(root,"p008_bad_oversize_transfer",r2,"oversize");
        writeTransfer(root,"p008_bad_conflict_r1_transfer",revised(r1,1,2),"");
        writeTransfer(root,"p008_bad_conflict_r2_transfer",revised(r1,2,2),"");
        writeTransfer(root,"p008_bad_stale_r1_transfer",r1,"");
        writeTransfer(root,"p008_qa_r1_replay_transfer",r1,"");
        System.out.println("P008_QA_NEGATIVE_CASES_PREPARED scope=DECLARED_SYNTHETIC_FILES_ONLY native_submission=false expected_checks=missing_page,fragment_hash,wire_bound,same_revision_conflict,obsolete_revision,idempotent_replay");
    }
}
