package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real private IO and fresh-JVM proposal recovery. Synthetic metadata only.
 * Spooling is not a J04 commit, native acceptance, or physical qualification.
 */
public final class MetadataPlanSpoolTests {
    private MetadataPlanSpoolTests(){}
    private static final WorldId WORLD=new WorldId(new UUID(18008,1));
    private static final ResourceId SOURCE=new ResourceId("fixture:p008_spool");
    private static final DimensionId DIM=new DimensionId("minecraft:overworld");
    private static final Tick CLOCK=new Tick(4242);
    private static final ModelCodec<MetadataGenerationPage> PAGE=new ModelCodec<>(MetadataGenerationPage.class);
    private static final StoreOptions OPTIONS=new StoreOptions(1_048_620,4_194_304,16_777_216,33_554_432,64,0,128);
    private static int assertions;
    private static void check(boolean yes,String label){assertions++;if(!yes)throw new AssertionError(label);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void refuse(Action action,String label)throws Exception {
        try{action.run();throw new AssertionError("accepted "+label);}
        catch(IOException|IllegalArgumentException expected){assertions++;}
    }
    private static LocationTransferManifest manifest(long revision) {
        var regions=new ArrayList<ImportedRegion>();
        for(int i=0;i<2;i++)regions.add(new ImportedRegion(new RegionId(new UUID(18009,i)),DIM,new ImportBounds(i,0,i,i+1,1,i+1),Set.of()));
        var unsigned=new LocationImport(1,WORLD,SOURCE,new Revision(revision),new Fingerprint("0".repeat(64)),regions,List.of(),List.of());
        var source=new LocationImport(1,WORLD,SOURCE,unsigned.exportRevision(),StandaloneLocationImporter.digest(unsigned),regions,List.of(),List.of());
        return LocationTransfer.prepare(source).manifest();
    }
    private static RegionAggregate region(int ordinal,Tick tick,int count) {
        var boxes=new ArrayList<Aabb>();boxes.add(new Aabb(DIM,ordinal,0,ordinal,ordinal+1,1,ordinal+1));
        for(int i=0;i<count;i++)boxes.add(new Aabb(DIM,1_000_000_000+i,1_000_000_000,1_000_000_000+i,
            1_000_000_001+i,1_000_000_001,1_000_000_001+i));
        var id=new RegionId(new UUID(18009,ordinal));
        return new RegionAggregate(new RegionRecord(new AggregateHeader(1,id.value(),new Revision(2),new Sequence(0),tick),id,DIM,
            RegionStatus.ACTIVE,new SupportShape(boxes,List.of(),List.of()),Set.of(),new UnitValue(0),Set.of(),Set.of(),
            new Revision(1),new RegionPersonality(Map.of()),Set.of()));
    }
    private static List<AggregateState> states(){return List.of(region(0,CLOCK,4000),region(1,CLOCK,4000));}
    private static Map<AggregateKey,Revision> guards(List<AggregateState> states) {
        var result=new LinkedHashMap<AggregateKey,Revision>();
        for(var row:states){result.put(row.key(),new Revision(1));result.put(new AggregateKey(AggregateKind.REGION_MEMORY,row.key().id()),new Revision(4));}
        return Map.copyOf(result);
    }
    private static Path spool(Path root,LocationTransferManifest manifest){return root.resolve(".generation-plan-"+LocationTransfer.identity(manifest).value());}
    private static Map<String,Fingerprint> files(Path root)throws IOException {
        var result=new TreeMap<String,Fingerprint>();try(var entries=Files.walk(root)) {
            for(var file:entries.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())
                result.put(root.relativize(file).toString(),BlobContent.hash(Files.readAllBytes(file)));
        }return Map.copyOf(result);
    }
    private static void exactPreparation(Path root)throws Exception {
        Files.createDirectory(root);var manifest=manifest(2);var states=states();var guards=guards(states);
        check(MetadataPlanSpool.preparedTick(root,manifest).isEmpty(),"missing spool has no prepared clock");
        var references=MetadataPlanSpool.prepare(root,manifest,0,states,guards);
        check(references.size()>1,"large preserved runtime pair packed into multiple files");
        long sum=0;var actualStates=new HashMap<AggregateKey,AggregateState>();var actualGuards=new HashMap<AggregateKey,Revision>();
        for(int i=0;i<references.size();i++) {
            var reference=references.get(i);sum+=reference.bytes();
            check(reference.bytes()>0&&reference.bytes()<=1_048_576,"each actual spool file bounded");
            check(BlobContent.hash(Files.readAllBytes(reference.path())).equals(reference.sha256()),"actual stored plan fingerprint exact");
            var page=MetadataPlanSpool.read(reference,manifest,i,references.size());
            check(page.pageIndex()==i&&page.pageCount()==references.size(),"final total counters frozen consistently");
            check(PAGE.decode(PAGE.encode(page)).equals(page),"final encoded counters and full state round trip");
            check(PAGE.encode(page).length<=reference.bytes(),"final page fits placeholder packing bound");
            for(var row:page.states())check(actualStates.put(row.key(),row)==null,"no state duplicated between files");
            for(var entry:page.beforeRevisions().entrySet())check(actualGuards.put(entry.getKey(),entry.getValue())==null,"no unchanged guard duplicated between files");
        }
        check(actualStates.size()==2&&states.stream().allMatch(s->s.equals(actualStates.get(s.key()))),"all large runtime geometry preserved exactly");
        check(actualGuards.equals(guards),"all exact state and unchanged region-memory guards preserved");
        check(MetadataPlanSpool.preparedTick(root,manifest).equals(Optional.of(CLOCK)),"proposal clock recovered before any J04 stage");
        var before=files(root);var again=MetadataPlanSpool.prepare(root,manifest,0,states,guards);
        check(references.equals(again)&&before.equals(files(root)),"replay reuses exact immutable files without rewriting bytes");
        refuse(()->MetadataPlanSpool.read(references.getFirst(),manifest(3),0,references.size()),"a different frozen source identity");
        refuse(()->MetadataPlanSpool.read(references.getFirst(),manifest,0,1025),"excess final page count");
        refuse(()->MetadataPlanSpool.prepare(root,manifest,1024,states,guards),"excess planning group");
        var altered=List.<AggregateState>of(region(0,new Tick(CLOCK.value()+1),4000),region(1,new Tick(CLOCK.value()+1),4000));
        refuse(()->MetadataPlanSpool.prepare(root,manifest,0,altered,guards),"changed proposal after preparation");
        check(before.equals(files(root)),"refused replan preserves every historical prepared byte");
        System.out.println("SPOOL_PAIR pages="+references.size()+" bytes="+sum+" clock="+CLOCK.value());
    }
    private static void crashBeforeStage(Path root)throws Exception {
        Path log=root.resolveSibling(root.getFileName()+"-child.log");
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-ea","-Xmx384m","-XX:ActiveProcessorCount=1",
            "-cp",System.getProperty("java.class.path"),MetadataPlanSpoolTests.class.getName(),"halt-after-prepare",root.toString());
        var child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("owned spool child timeout");}
        check(child.exitValue()==73,"actual child JVM halted after forced preparation before any native stage");
        String original=Files.readString(log);check(original.contains("SPOOL_PROPOSAL_READY")&&original.contains("J04_STAGE=false"),"preparation journal explicitly precedes first native stage without a fake ACK");
        Path recoveryLog=log.resolveSibling(log.getFileName()+"-fresh-recovery.log");
        var recoveryCommand=new ArrayList<>(command);recoveryCommand.set(recoveryCommand.size()-2,"read-clock");
        var reader=new ProcessBuilder(recoveryCommand).redirectErrorStream(true).redirectOutput(recoveryLog.toFile()).start();
        if(!reader.waitFor(30,TimeUnit.SECONDS)){reader.destroyForcibly();throw new AssertionError("owned spool recovery child timeout");}
        check(reader.exitValue()==0,"newly launched recovery JVM reads prepared proposal after original halt");
        String recovered=Files.readString(recoveryLog);
        check(recovered.contains("RECOVERED_PROPOSAL_CLOCK="+CLOCK.value()),"fresh JVM obtains original proposal clock from forced files");
        check(!processStart(original).equals(processStart(recovered)),"actual separate JVM start times distinguish halted and recovery processes");
        var manifest=manifest(2);var before=files(root);
        check(MetadataPlanSpool.preparedTick(root,manifest).equals(Optional.of(CLOCK)),"new JVM recovers original proposal clock despite no J04 stage");
        var restored=MetadataPlanSpool.prepare(root,manifest,0,states(),guards(states()));
        check(before.equals(files(root)),"new JVM exact resume preserves forced files");
        check(restored.size()>1,"all prepared chunks recovered after abrupt child termination");
        for(int i=0;i<restored.size();i++)check(MetadataPlanSpool.read(restored.get(i),manifest,i,restored.size()).pageCount()==restored.size(),"resumed final count consistent");
    }
    private static String processStart(String output) {
        var found=java.util.regex.Pattern.compile("START_MS=([0-9]+)").matcher(output);
        if(!found.find())throw new AssertionError("missing actual JVM identity in journal");return found.group(1);
    }
    private static void corruptionAndLinks(Path root)throws Exception {
        Files.createDirectory(root);var manifest=manifest(2);var refs=MetadataPlanSpool.prepare(root,manifest,0,states(),guards(states()));
        var first=refs.getFirst();byte[] altered=Files.readAllBytes(first.path());altered[0]^=1;Files.write(first.path(),altered);
        var corrupt=BlobContent.hash(altered);
        refuse(()->MetadataPlanSpool.read(first,manifest,0,refs.size()),"corrupted prepared file");
        refuse(()->MetadataPlanSpool.preparedTick(root,manifest),"corrupted clock proposal");
        check(BlobContent.hash(Files.readAllBytes(first.path())).equals(corrupt),"corrupted evidence retained after refusals");
        Path link=root.resolve("file-link.json");Files.createSymbolicLink(link,refs.getLast().path());
        var linked=new MetadataPlanSpool.FileRef(link,refs.getLast().sha256(),refs.getLast().bytes());
        refuse(()->MetadataPlanSpool.read(linked,manifest,0,refs.size()),"symlink plan file");
        check(Files.isSymbolicLink(link),"symlink evidence preserved");
        Path alias=root.resolveSibling(root.getFileName()+"-alias");Files.createSymbolicLink(alias,root);
        refuse(()->MetadataPlanSpool.preparedTick(alias,manifest),"symlink spool parent");
        check(Files.isSymbolicLink(alias),"symlink parent retained");
        Path next=root.resolve("next");Files.createDirectory(next);
        var newRefs=MetadataPlanSpool.prepare(next,manifest(3),0,states(),guards(states()));
        check(!newRefs.isEmpty()&&Files.exists(first.path()),"new source generation uses distinct spool while keeping previous evidence");
    }
    private static void budgetsBeforeWrites(Path root)throws Exception {
        Files.createDirectory(root);var manifest=manifest(2);var states=states();var guards=guards(states);
        Path bytes=root.resolve("bytes-refusal");
        refuse(()->MetadataPlanSpool.prepare(bytes,manifest,0,states,guards,1L,1024,0L),"insufficient remaining generation bytes");
        check(!Files.exists(bytes),"byte refusal happens before creating any spool directory");
        Path count=root.resolve("count-refusal");
        refuse(()->MetadataPlanSpool.prepare(count,manifest,0,states,guards,64L*1024*1024,1,0L),"insufficient remaining page count");
        check(!Files.exists(count),"page-count refusal happens before creating any spool directory");
        Path free=root.resolve("free-refusal");long impossibleReserve=Files.getFileStore(root).getUsableSpace()+1;
        refuse(()->MetadataPlanSpool.prepare(free,manifest,0,states,guards,64L*1024*1024,1024,impossibleReserve),"required free disk reserve");
        check(!Files.exists(free),"disk-reserve refusal happens before creating any spool directory");
        Path existing=root.resolve("existing");var prepared=MetadataPlanSpool.prepare(existing,manifest,0,states,guards);
        var prior=files(existing);long total=prepared.stream().mapToLong(MetadataPlanSpool.FileRef::bytes).sum();
        refuse(()->MetadataPlanSpool.prepare(existing,manifest,0,states,guards,total-1,prepared.size(),0L),"cumulative budget excluding already consumed groups");
        check(prior.equals(files(existing)),"refused cumulative budget preserves every prior prepared byte");
        var exact=MetadataPlanSpool.prepare(existing,manifest,0,states,guards,total,prepared.size(),0L);
        check(exact.equals(prepared)&&prior.equals(files(existing)),"exact remaining logical budget permits immutable replay");
    }
    private static void recoveryScanBound(Path root)throws Exception {
        Files.createDirectory(root);var manifest=manifest(2);Path directory=spool(root,manifest);Files.createDirectories(directory);
        for(int i=0;i<1025;i++)Files.writeString(directory.resolve(String.format(Locale.ROOT,"group-%04d-chunk-%03d.json",i/2,i%2)),"");
        try{MetadataPlanSpool.preparedTick(root,manifest);throw new AssertionError("accepted recovery scan above1024files");}
        catch(IllegalArgumentException refused){check(refused.getMessage().contains("file count bound"),"recovery refuses excessive file set before decoding empty/untrusted bodies");}
        try(var files=Files.list(directory)){check(files.count()==1025,"all excessive historical evidence retained after refusal");}
        Path orphan=root.resolve("orphan-only");Path orphanDirectory=spool(orphan,manifest);Files.createDirectories(orphanDirectory);
        Files.writeString(orphanDirectory.resolve(".plan-unpublished.tmp"),"unpublished partial bytes");
        check(MetadataPlanSpool.preparedTick(orphan,manifest).isEmpty(),"unpublished temporary file never supplies a proposal clock");
        check(Files.readString(orphanDirectory.resolve(".plan-unpublished.tmp")).equals("unpublished partial bytes"),"orphan evidence retained without activation");
    }
    private static void spoolIsNotAuthority(Path root)throws Exception {
        Files.createDirectory(root);var store=JournalStore.open(root.resolve("real-j04"),WORLD,OPTIONS).toCompletableFuture().get(30,TimeUnit.SECONDS);
        try {
            var before=store.metadataSnapshot().orElseThrow();
            MetadataPlanSpool.prepare(root.resolve("inputs"),manifest(2),0,states(),guards(states()));
            var after=store.metadataSnapshot().orElseThrow();
            check(before.cut().equals(after.cut())&&before.generations().equals(after.generations())&&after.generations().isEmpty(),"real J04 cut and active generations unchanged by private spool");
            var page=store.metadataKeysPage(after,Set.of(AggregateKind.REGION),Optional.empty(),96).orElseThrow();
            check(page.keys().isEmpty(),"spooling alone publishes no real metadata head");
        }finally{store.closeAsync().toCompletableFuture().get(30,TimeUnit.SECONDS);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("halt-after-prepare")) {
            Path root=Path.of(args[1]);Files.createDirectory(root);MetadataPlanSpool.prepare(root,manifest(2),0,states(),guards(states()));
            System.out.println("SPOOL_PROPOSAL_READY PID="+ProcessHandle.current().pid()+" START_MS="+java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()
                +" SOURCE="+LocationTransfer.identity(manifest(2)).value()+" J04_STAGE=false NATIVE_ACK=false");System.out.flush();
            Runtime.getRuntime().halt(73);throw new AssertionError("halt returned");
        }
        if(args.length==2&&args[0].equals("read-clock")) {
            var recovered=MetadataPlanSpool.preparedTick(Path.of(args[1]),manifest(2));
            if(!recovered.equals(Optional.of(CLOCK)))throw new AssertionError("original proposal clock missing");
            System.out.println("RECOVERED_PROPOSAL_CLOCK="+recovered.orElseThrow().value()+" PID="+ProcessHandle.current().pid()
                +" START_MS="+java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()+" NATIVE_ACK=false");return;
        }
        if(args.length!=1)throw new IllegalArgumentException("NEW private QA root required");
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectory(root);
        exactPreparation(root.resolve("exact"));crashBeforeStage(root.resolve("crash"));
        corruptionAndLinks(root.resolve("corruption"));budgetsBeforeWrites(root.resolve("budgets"));recoveryScanBound(root.resolve("scan-bound"));spoolIsNotAuthority(root.resolve("authority"));
        System.out.println("PASS MetadataPlanSpoolTests assertions="+assertions+" scope=PRIVATE_IO_SYNTHETIC_NEW_JVM_PROPOSAL_NOT_NATIVE_ACCEPTANCE");
    }
}
