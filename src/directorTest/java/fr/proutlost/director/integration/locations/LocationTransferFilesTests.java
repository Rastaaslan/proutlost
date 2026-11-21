package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Declared synthetic filesystem producer test; no native/physical acceptance. */
public final class LocationTransferFilesTests {
    private static int assertions;
    private static final WorldId WORLD=new WorldId(new UUID(8008,1));
    private static final DimensionId DIMENSION=new DimensionId("minecraft:overworld");
    private static final ModelCodec<LocationTransferManifest> MANIFEST=new ModelCodec<>(LocationTransferManifest.class);
    private static final ModelCodec<LocationTransferPage> PAGE=new ModelCodec<>(LocationTransferPage.class);
    private LocationTransferFilesTests(){}
    private static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void refuse(Action action,String message)throws Exception {
        try{action.run();throw new AssertionError("accepted "+message);}
        catch(IOException|IllegalArgumentException expected){assertions++;}
    }
    private static LocationImport source(int count,long revision) {
        var regions=new ArrayList<ImportedRegion>();var locations=new ArrayList<ImportedLocation>();var anchors=new ArrayList<ImportedAnchor>();
        for(int i=0;i<count;i++) {
            var region=new RegionId(new UUID(8009,i));var location=new LocationId(new UUID(8010,i));var anchor=new AnchorId(new UUID(8011,i));
            var bounds=new ImportBounds(i*32,60,0,i*32+24,80,24);
            regions.add(new ImportedRegion(region,DIMENSION,bounds,Set.of()));
            locations.add(new ImportedLocation(location,"synthetic-site-"+i,Set.of(region),DIMENSION,bounds,Set.of(new TagId("proutlost:structure"))));
            anchors.add(new ImportedAnchor(anchor,location,DIMENSION,new ImportPosition(i*32+10,64,10),Set.of(new TagId("proutlost:anchor_center"))));
        }
        var draft=new LocationImport(1,WORLD,new ResourceId("fixture:p008_producer"),new Revision(revision),new Fingerprint("0".repeat(64)),regions,locations,anchors);
        return new LocationImport(1,WORLD,draft.sourceNamespace(),draft.exportRevision(),StandaloneLocationImporter.digest(draft),regions,locations,anchors);
    }
    private static LocationImport decode(Path directory)throws Exception {
        var manifest=MANIFEST.decode(Files.readAllBytes(directory.resolve("manifest.json")));
        var output=new ByteArrayOutputStream();
        // Deliberately enumerate in reverse, as an independent receiving observer.
        var files=new ArrayList<Path>();try(var list=Files.list(directory)){files.addAll(list.filter(p->p.getFileName().toString().startsWith("page-")).toList());}
        files.sort(Comparator.reverseOrder());var received=new TreeMap<Integer,byte[]>();
        for(var file:files) {
            byte[] wire=Files.readAllBytes(file);check(wire.length<=32768,"actual message bound");
            var page=PAGE.decode(wire);check(Arrays.equals(wire,PAGE.encode(page)),"canonical page bytes");
            received.put(page.pageIndex(),LocationTransfer.fragment(manifest,page));
        }
        check(received.size()==manifest.pageHashes().size(),"every original page retained");
        received.values().forEach(output::writeBytes);byte[] bytes=output.toByteArray();
        var value=new ModelCodec<>(LocationImport.class).decode(bytes);LocationTransfer.matches(manifest,value,bytes);
        return value;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("NEW private QA directory required");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectory(root);
        var input=source(400,1);refuse(()->StandaloneLocationImporter.requireNativeEnvelope(input),"large source in ordinary native envelope");
        var target=root.resolve("synthetic400_transfer");
        var first=LocationTransferFiles.writeImmutable(target,input);
        check(first.written()&&first.pages()>1,"complete large source published as several messages");
        check(input.equals(decode(target)),"all400 locations/regions/anchors independently received exactly");
        check(first.sourceSha256().equals(BlobContent.hash(new ModelCodec<>(LocationImport.class).encode(input))),"source provenance hash exact");
        byte[] manifest=Files.readAllBytes(target.resolve("manifest.json"));
        var replay=LocationTransferFiles.writeImmutable(target,input);
        check(!replay.written()&&replay.manifestSha256().equals(first.manifestSha256()),"immutable replay verifies without replacement");
        check(Arrays.equals(manifest,Files.readAllBytes(target.resolve("manifest.json"))),"original manifest unchanged");
        refuse(()->LocationTransferFiles.writeImmutable(target,source(399,2)),"same folder with a different generation");
        check(input.equals(decode(target)),"refused replacement retains all original source bytes");
        Path extra=target.resolve("extra.json");Files.writeString(extra,"{}");
        refuse(()->LocationTransferFiles.writeImmutable(target,input),"extra transport file");
        check(Files.readString(extra).equals("{}"),"unexpected evidence file retained");
        var next=root.resolve("synthetic399r2_transfer");LocationTransferFiles.writeImmutable(next,source(399,2));
        check(decode(next).exportRevision().equals(new Revision(2)),"new immutable generation independently published");
        Path occupied=root.resolve("occupied_transfer");Files.writeString(occupied,"preserved");
        refuse(()->LocationTransferFiles.writeImmutable(occupied,input),"existing ordinary file");
        check(Files.readString(occupied).equals("preserved"),"ordinary target preserved");
        Path linked=root.resolve("linked_transfer");Files.createSymbolicLink(linked,next);
        refuse(()->LocationTransferFiles.writeImmutable(linked,input),"symlink target");
        check(Files.isSymbolicLink(linked),"symlink evidence retained");
        Path huge=root.resolve("oversize_transfer");refuse(()->LocationTransferFiles.writeImmutable(huge,source(1500,3)),"source above1MiB");
        check(!Files.exists(huge),"oversize transfer never published");
        check(!Files.exists(target.resolve("CURRENT.json"))&&!Files.exists(target.resolve("ACK.json")),"producer never manufactures native authority");
        System.out.println("P008_PRODUCER_TRANSFER_FILES_PASS assertions="+assertions+" synthetic_locations=400 native_import=false physical_reception=false");
    }
}
