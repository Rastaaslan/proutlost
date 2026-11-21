package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.locations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A disjoint, declared synthetic baseline for non-vacuous other-source retention.
 * Writes transport inputs only, never a J04 state or an admission receipt. */
public final class NativeOtherLocationQaInput {
    private NativeOtherLocationQaInput(){}
    private static UUID id(WorldId world,String kind){return UUID.nameUUIDFromBytes(
        ("P008-other-source-fixture/"+world.value()+"/"+kind).getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("QA_WORLD_ROOT WORLD_UUID");
        Path root=Path.of(args[0]).toRealPath();
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("symlink QA root");
        Path marker=root.resolve("TEST_ONLY");
        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||Files.size(marker)>128
            ||!Files.readString(marker).trim().equals("PROUTLOST_NEUTRAL_QA_V1"))throw new IllegalArgumentException("explicit TEST_ONLY QA world required");
        var world=new WorldId(UUID.fromString(args[1]));var source=new ResourceId("qa:p008_l2_other");
        var dimension=new DimensionId("minecraft:overworld");var region=new RegionId(id(world,"region"));
        var location=new LocationId(id(world,"location"));var anchor=new AnchorId(id(world,"anchor"));
        var regions=List.of(new ImportedRegion(region,dimension,new ImportBounds(2_000_000,50,2_000_000,2_000_064,80,2_000_064),Set.of(new TagId("proutlost:clearing"))));
        var locations=List.of(new ImportedLocation(location,"p008-disjoint-other-source",Set.of(region),dimension,
            new ImportBounds(2_000_002,60,2_000_002,2_000_007,70,2_000_007),Set.of(new TagId("proutlost:structure"))));
        var anchors=List.of(new ImportedAnchor(anchor,location,dimension,new ImportPosition(2_000_004,64,2_000_004),Set.of(new TagId("proutlost:anchor_center"))));
        var draft=new LocationImport(1,world,source,new Revision(1),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        var value=new LocationImport(1,world,source,draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,locations,anchors);
        var prepared=LocationTransfer.prepare(value);byte[] bytes=new ModelCodec<>(LocationImport.class).encode(value);
        if(prepared.pages().size()!=1||bytes.length>LocationTransfer.WIRE_BYTES)throw new AssertionError("small baseline contract");
        Path inputs=root.resolve("director-location-imports");
        if(Files.isSymbolicLink(inputs))throw new IllegalArgumentException("symlink inputs");
        Files.createDirectories(inputs);Path directory=inputs.resolve("p008_other_transfer");Files.createDirectory(directory);
        Files.write(inputs.resolve("p008_other_transfer-expected.json"),bytes,StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("manifest.json"),new ModelCodec<>(LocationTransferManifest.class).encode(prepared.manifest()),StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("page-000.json"),new ModelCodec<>(LocationTransferPage.class).encode(prepared.pages().getFirst()),StandardOpenOption.CREATE_NEW);
        System.out.println("P008_DECLARED_OTHER_SOURCE_INPUT source="+source.value()+" source_sha256="+BlobContent.hash(bytes).value()
            +" manifest_sha256="+LocationTransfer.identity(prepared.manifest()).value()+" heads=4 geometry_disjoint=true native_admission=NOT_RUN");
    }
}
