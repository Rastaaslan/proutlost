package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Private IO proposals only. These files grant no J04/native authority. */
public final class MetadataAttemptSpoolTests {
    private static final WorldId WORLD=new WorldId(new UUID(18180,1));
    private static final ResourceId SOURCE=new ResourceId("fixture:attempt_spool");
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static int assertions;
    private MetadataAttemptSpoolTests(){}
    private static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
    @FunctionalInterface private interface Checked{void run()throws Exception;}
    private static void refused(Checked action,String why)throws Exception{
        try{action.run();throw new AssertionError("accepted "+why);}catch(IllegalArgumentException|IOException expected){check(true,why);}
    }
    private static LocationTransferManifest manifest(){
        var row=new ImportedLocation(new LocationId(new UUID(18180,2)),"fixture-site",Set.of(),DIM,new ImportBounds(0,0,0,1,1,1),Set.of());
        var input=new LocationImport(1,WORLD,SOURCE,new Revision(2),new Fingerprint("0".repeat(64)),List.of(),List.of(row),List.of());
        return LocationTransfer.prepare(new LocationImport(1,WORLD,SOURCE,input.exportRevision(),StandaloneLocationImporter.digest(input),List.of(),List.of(row),List.of())).manifest();
    }
    private static List<AggregateState> states(long tick){
        var id=new LocationId(new UUID(18180,2));
        return List.of(new LocationAggregate(new LocationRecord(new AggregateHeader(1,id.value(),new Revision(2),new Sequence(0),new Tick(tick)),
            id,SOURCE,"fixture-site",DIM,new Aabb(DIM,0,0,0,1,1,1),Set.of(),Set.of(),new Revision(2),true)));
    }
    private static List<MetadataPlanSpool.FileRef> prepare(Path root,Epoch epoch,long tick)throws Exception{
        var states=states(tick);return MetadataPlanSpool.prepare(root,manifest(),epoch,0,states,Map.of(states.getFirst().key(),new Revision(1)),64L*1024*1024,1024,0);
    }
    private static Path directory(Path root){return root.resolve(".generation-plan-"+LocationTransfer.identity(manifest()).value());}
    private static Map<String,Fingerprint> files(Path root)throws Exception{
        var result=new TreeMap<String,Fingerprint>();try(var entries=Files.list(directory(root))){for(var p:entries.toList())result.put(p.getFileName().toString(),BlobContent.hash(Files.readAllBytes(p)));}return Map.copyOf(result);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("NEW_PRIVATE_ROOT");var root=Path.of(args[0]);Files.createDirectory(root);
        var exact=root.resolve("exact");Files.createDirectory(exact);
        var first=prepare(exact,new Epoch(1),120000);var frozen=files(exact);
        check(first.equals(prepare(exact,new Epoch(1),120000))&&frozen.equals(files(exact)),"same attempt exact proposal reuses immutable bytes");
        var next=prepare(exact,new Epoch(2),150000);check(!first.equals(next),"different attempt keeps separate immutable private proposal");
        var changed=prepare(exact,new Epoch(2),150001);check(!next.equals(changed),"unstaged private proposal does not freeze the J04-authoritative read set");
        var after=files(exact);for(var saved:frozen.entrySet())check(saved.getValue().equals(after.get(saved.getKey())),"old private proposal remains byte-identical");
        check(MetadataPlanSpool.read(first.getFirst(),manifest(),0,1).states().getFirst().header().updatedTick().value()==120000,"old attempt file remains readable");
        check(MetadataPlanSpool.preparedTick(exact,manifest()).isEmpty(),"later attempt files cannot impersonate original legacy clock authority");
        var incomplete=directory(exact).resolve(".plan-interrupted.tmp");Files.createFile(incomplete);var retained=files(exact);
        prepare(exact,new Epoch(3),160000);check(Files.exists(incomplete)&&Files.size(incomplete)==0&&files(exact).keySet().containsAll(retained.keySet()),"incomplete private temporary is preserved and bounded without blocking a J04-authorized rebuild");
        var count=root.resolve("count");Files.createDirectory(count);Files.createDirectory(directory(count));
        for(int i=0;i<1024;i++)Files.write(directory(count).resolve("saved-"+i+".tmp"),new byte[]{1});
        refused(()->prepare(count,new Epoch(4),170000),"all attempts share 1024 retained private files");
        try(var entries=Files.list(directory(count))){check(entries.count()==1024,"file-count refusal occurs before writing new proposal");}
        var bytes=root.resolve("bytes");Files.createDirectory(bytes);Files.createDirectory(directory(bytes));
        for(int i=0;i<64;i++)try(var out=new RandomAccessFile(directory(bytes).resolve("saved-"+i+".tmp").toFile(),"rw")){out.setLength(1_048_576);}
        refused(()->prepare(bytes,new Epoch(5),180000),"all attempts share 64MiB retained private bytes");
        try(var entries=Files.list(directory(bytes))){check(entries.count()==64,"byte refusal occurs before writing new proposal");}
        refused(()->MetadataPlanSpool.prepare(exact,manifest(),new Epoch(6),0,states(190000),Map.of(states(190000).getFirst().key(),new Revision(1)),64L*1024*1024,1024,Long.MAX_VALUE/2),"disk reserve remains effective on later attempts");
        check(files(exact).values().containsAll(frozen.values()),"capacity refusals preserve old evidence");
        System.out.println("PASS MetadataAttemptSpoolTests assertions="+assertions+" scope=PRIVATE_IO_SYNTHETIC_ONLY_NOT_J04_AUTHORITY_OR_NATIVE_ACCEPTANCE");
    }
}
