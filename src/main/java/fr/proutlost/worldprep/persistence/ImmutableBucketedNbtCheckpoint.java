package fr.proutlost.worldprep.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * EXPERIMENTAL, NOT ENABLED on any live Minecraft world.
 *
 * Full legacy NBT envelope -> small, vanilla-GZIP NBT index + immutable,
 * SHA-256-addressed, bucketed NBT pages. Each leaf is write+fsync+publish
 * BEFORE the external caller atomically replaces the compact root .dat.
 * A crash before that root swap leaves the previous full snapshot authoritative.
 *
 * Only sections with stable identity keys are paged. The two insertion-order
 * sections (areas/jobs) retain each record's original ordinal; all others
 * restore their natural TreeMap order. No record is dropped or silently
 * defaulted. Expanding old-format, non-paged envelopes is a no-op.
 *
 * Integration with WorldPrep runtime and external cold auditors requires a
 * separate admission/migration protocol; this class alone does NOT authorize
 * changing the production .dat format or existing receipt-chain semantics.
 */
public final class ImmutableBucketedNbtCheckpoint {
    public static final int VERSION=1;
    public static final int BUCKETS=1024;
    private static final String MARKER="__worldprep_bucket_index_v1";
    private static final int MAX_ENTRY_COUNT=1_000_000;
    private static final long MAX_PAGE_UNCOMPRESSED=12L*1024*1024;
    private static final long MAX_PAGE_COMPRESSED=12L*1024*1024;
    private static final Set<String> NAMES=Set.of(
            "areas","jobs","plans","journals","snapshots");
    private static final Set<String> STORES=Set.of(
            "proutlost_worldprep","proutlost_worldprep_blocks");
    /** In-JVM stat cache ONLY for writes; every cold read still rehashes content.
     * Never trust a cached page if size, mtime or inode changes externally.
     */
    private record PageStat(long size,long modified, Object fileKey) {}
    private static final java.util.concurrent.ConcurrentHashMap<Path,PageStat> VERIFIED_PAGES=
            new java.util.concurrent.ConcurrentHashMap<>();
    private static PageStat stat(Path p) throws IOException {
        var a=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.isSymbolicLink())throw new IOException("Immutable page is not a regular file");
        return new PageStat(a.size(),a.lastModifiedTime().toMillis(),a.fileKey());
    }

    /** Test instrumentation counts, never persisted into a Minecraft world. */
    public record Metrics(int referencedPages,int newPages,long newPageBytes,long manifestBytes) {}
    public record Capture(CompoundTag compactRoot,Metrics metrics) {}
    private record Record(String key,int order,CompoundTag payload){}
    private record Ref(int bucket,String sha,int count){}
    private record PendingPage(Path temp,Path finalPath,String sha){}
    private record BucketKey(Path checkpoint,String store,String section,int bucket){}
    private record CachedPage(String sha,List<Record> immutableRecords){}
    private static final java.util.concurrent.ConcurrentHashMap<BucketKey,CachedPage> BUCKET_CACHE=
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String,Integer> PARTITION_CACHE=
            new java.util.concurrent.ConcurrentHashMap<>();

    public static Capture externalize(CompoundTag full, Path target, String store) throws IOException {
        if(!STORES.contains(store))throw new IOException("Unrecognized protected WorldPrep SavedData store");
        if(full==null || !full.contains("data",Tag.TAG_COMPOUND))
            throw new IOException("Full NBT envelope missing compound data");
        var source=full.getCompound("data");
        if(source.contains(MARKER))throw new IOException("Double paged conversion refused");
        // Do NOT deep-copy the multi-megabyte unchanged historical lists.
        // The input is already a completed immutable SavedData capture.
        var compact=new CompoundTag();
        for(var field:full.getAllKeys())if(!field.equals("data"))
            compact.put(field,full.get(field).copy());
        var data=new CompoundTag();
        for(var field:source.getAllKeys())if(!NAMES.contains(field))
            data.put(field,source.get(field).copy());
        compact.put("data",data);
        var index=new CompoundTag();
        index.putInt("version",VERSION);
        index.putInt("bucketCount",BUCKETS);
        index.putString("store",store);
        var sections=new ListTag();
        int totalPages=0,newPages=0;
        long newPageBytes=0;
        var pending=new ArrayList<PendingPage>();
        var newCacheEntries=new HashMap<BucketKey,CachedPage>();
        // Stat cache is only an acceleration, never a required authority.
        // Restrict retained stat entries to pages of the CURRENT checkpoint.
        var currentPagePaths=new HashSet<Path>();
        for(var name: NAMES.stream().sorted().toList()){
            if(!source.contains(name,Tag.TAG_LIST))continue;
            var list=source.getList(name,Tag.TAG_COMPOUND);
            if(list.size()>MAX_ENTRY_COUNT)throw new IOException("Unsafe oversized records for "+name);
            // Other lists (such as immutable baseline overrides) remain legacy in
            // the compact .dat. We refuse an unexpected noncompound element.
            if(!list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND)
                throw new IOException("Unexpected tag type in "+name);
            boolean ordered=preserveInsertionOrder(store,name);
            @SuppressWarnings("unchecked")
            List<Record>[] buckets=(List<Record>[])new List[BUCKETS];
            for(int i=0;i<BUCKETS;i++)buckets[i]=new ArrayList<>();
            Set<String> keys=new HashSet<>(Math.min(list.size()*2+1,MAX_ENTRY_COUNT*2));
            for(int i=0;i<list.size();i++){
                var entry=list.getCompound(i);
                String key=keyFor(store,name,entry);
                if(!keys.add(key))throw new IOException("Duplicate stable record key "+name+" "+key);
                int bucket=partition(key);
                buckets[bucket].add(new Record(key,ordered?i:-1,entry));
            }
            var sec=new CompoundTag();
            sec.putString("name",name);
            sec.putInt("count",list.size());
            sec.putBoolean("ordered",ordered);
            var bucketRefs=new ListTag();
            if(Boolean.getBoolean("proutlost.run52.parallelNbtBuckets")){
                // RUN52 DEV: captured NBT is immutable. Encode disjoint bucket ranges
                // with four bounded CPU workers, but publish/index/ACK ONLY in
                // canonical bucket order AFTER all workers returned successfully.
                // Failed futures leave the prior root authoritative.
                for(var result:parallelBucketEncode(target,store,name,buckets)){
                    if(result.staged()!=null){
                        pending.add(result.staged());
                        newPages++;
                        newPageBytes+=Files.size(result.staged().temp());
                    }
                    if(result.cache()!=null)
                        newCacheEntries.put(new BucketKey(target.toAbsolutePath().normalize(),
                                store,name,result.bucket()),result.cache());
                    currentPagePaths.add(pagePath(target,store,name,result.sha()));
                    var ref=new CompoundTag();
                    ref.putInt("bucket",result.bucket());
                    ref.putString("sha256",result.sha());
                    ref.putInt("count",result.count());
                    bucketRefs.add(ref);
                    totalPages++;
                }
            } else
            for(int b=0;b<BUCKETS;b++){
                if(buckets[b].isEmpty())continue;
                var cacheKey=new BucketKey(target.toAbsolutePath().normalize(),store,name,b);
                var cached=BUCKET_CACHE.get(cacheKey);
                String digest;
                if(cached!=null && cached.immutableRecords().equals(buckets[b])){
                    digest=cached.sha();
                    Path existing=pagePath(target,store,name,digest);
                    if(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Previously committed unchanged bucket page missing");
                    var current=stat(existing);
                    if(!current.equals(VERIFIED_PAGES.get(existing)))readPage(existing,digest);
                }else {
                    var page=new CompoundTag();
                    page.putInt("version",VERSION);
                    page.putString("store",store);
                    page.putString("section",name);
                    page.putInt("bucket",b);
                    var entries=new ListTag();
                    for(var record:buckets[b]){
                        var wrapper=new CompoundTag();
                        wrapper.putString("key",record.key());
                        if(ordered)wrapper.putInt("order",record.order());
                        wrapper.put("record",record.payload());
                        entries.add(wrapper);
                    }
                    page.put("entries",entries);
                    byte[] raw=serialize(page);
                    if(raw.length>MAX_PAGE_UNCOMPRESSED)
                        throw new IOException("Oversized individual immutable page for "+name);
                    digest=digest(raw);
                    Path dest=pagePath(target,store,name,digest);
                    var staged=stagePage(dest,raw,digest);
                    if(staged!=null){
                        pending.add(staged);
                        newPages++;
                        newPageBytes+=Files.size(staged.temp());
                    }
                    // Deep-copy ONLY the buckets whose actual NBT content changed.
                    // Comparison on future saves is exact CompoundTag.equals, not a
                    // lossy hashCode heuristic or an unchecked dirty hint.
                    var frozen=new ArrayList<Record>(buckets[b].size());
                    for(var r:buckets[b])
                        frozen.add(new Record(r.key(),r.order(),r.payload().copy()));
                    newCacheEntries.put(cacheKey,new CachedPage(digest,List.copyOf(frozen)));
                }
                currentPagePaths.add(pagePath(target,store,name,digest));
                var ref=new CompoundTag();
                ref.putInt("bucket",b);
                ref.putString("sha256",digest);
                ref.putInt("count",buckets[b].size());
                bucketRefs.add(ref);
                totalPages++;
            }
            sec.put("buckets",bucketRefs);
            sections.add(sec);
            data.remove(name);
        }
        index.put("sections",sections);
        data.put(MARKER,index);
        // One syncfs flushes ALL staged data before publishing hash-addressed
        // names, then another flush durably publishes directory metadata.
        // The old root remains authoritative until caller completes ATOMIC_MOVE.
        commitStagedPages(pending,target);
        // Cache only after durable leaf publication. No cached decision can
        // turn a failed or incomplete I/O transaction into acknowledgement.
        BUCKET_CACHE.putAll(newCacheEntries);
        Path currentStoreDir=target.toAbsolutePath().getParent()
                .resolve("worldprep-paged-v1").resolve(store);
        VERIFIED_PAGES.keySet().removeIf(p->p.startsWith(currentStoreDir)
                &&!currentPagePaths.contains(p));
        if(PARTITION_CACHE.size()>350_000)PARTITION_CACHE.clear();
        return new Capture(compact,new Metrics(totalPages,newPages,newPageBytes,serialize(compact).length));
    }



    /**
     * RUN90 ISOLATED DEV: change-only immutable paged index publication.
     *
     * Reads the LAST atomically published native compact root. Only a bucket
     * containing an altered immutable record is reconstructed; every other
     * bucket retains its exact SHA reference. Each modified old leaf is fully
     * decoded and SHA verified. All new leaf files are forced before callers
     * atomically replace the root. The original v1 on-disk wire format and
     * cold full-hash loader are UNCHANGED.
     *
     * No deletion/reordering, missing prior index, noncanonical record key,
     * exceeding count, or foreign store can be accepted as a delta.
     */
    /**
     * RUN116 opt-in: verify EXACTLY the same old immutable page attributes and
     * SHA-on-stat-change as RUN90, but scan disjoint reference groups on the
     * existing bounded four-worker pool. No root/payload publication can occur
     * until ALL verifications returned successfully. Cold expansion still
     * independently re-hashes every referenced page.
     */
    private static void verifyRun116References(
            Path target,String store,String section,
            java.util.NavigableMap<Integer,CompoundTag> refs)throws IOException {
        var entries=new ArrayList<>(refs.values());
        var futures=new ArrayList<java.util.concurrent.CompletableFuture<Void>>(4);
        final int chunk=Math.max(1,(entries.size()+3)/4);
        for(int start=0;start<entries.size();start+=chunk){
            int lo=start,hi=Math.min(entries.size(),start+chunk);
            try{
                futures.add(java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{
                        for(int i=lo;i<hi;i++){
                            var ref=entries.get(i);
                            String digest=ref.getString("sha256");
                            Path path=pagePath(target,store,section,digest);
                            var prior=stat(path);
                            if(!prior.equals(VERIFIED_PAGES.get(path)))readPage(path,digest);
                        }
                    }catch(IOException e){throw new java.io.UncheckedIOException(e);}
                },RUN52_CPU_POOL));
            }catch(java.util.concurrent.RejectedExecutionException e){
                for(var f:futures) {
                    try{f.join();}catch(java.util.concurrent.CompletionException ignored){}
                }
                throw new IOException("RUN116 bounded reference verification queue saturated",e);
            }
        }
        try{
            java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }catch(java.util.concurrent.CompletionException e){
            var cause=e.getCause();
            if(cause instanceof java.io.UncheckedIOException io)throw io.getCause();
            throw new IOException("RUN116 prior-reference verification failed",cause);
        }
    }

    /** Sparse captures retain all inline metadata of the native loader's known schemas.
     * A richer current capture is never downgraded or stripped. */
    private static int requireSparseInlineMetadata(CompoundTag data)throws IOException {
        if(!data.contains("schema",Tag.TAG_INT))throw new IOException("RUN90 missing parent schema");
        int schema=data.getInt("schema");
        if(schema!=4&&schema!=5&&schema!=6&&schema!=WorldPrepSavedData.SCHEMA)
            throw new IOException("RUN90 unsupported parent metadata schema "+schema);
        for(var name:List.of("selections","protections","overrides"))
            requireSparseCompoundList(data,name);
        boolean current=schema==WorldPrepSavedData.SCHEMA;
        boolean marked=data.contains("locationRegistrySchema");
        if((current||marked)&&(!data.contains("locationRegistrySchema",Tag.TAG_INT)
                ||data.getInt("locationRegistrySchema")!=1
                ||!data.contains("locations",Tag.TAG_COMPOUND)))
            throw new IOException("RUN90 missing/invalid marked location registry");
        if(current||data.contains("analyses"))requireSparseCompoundList(data,"analyses");
        if(data.contains("locations")){
            if(!data.contains("locations",Tag.TAG_COMPOUND))
                throw new IOException("RUN90 invalid authored location metadata tag");
            try {fr.proutlost.worldprep.location.LocationRegistryNbt.load(data.getCompound("locations"));}
            catch(RuntimeException invalid){throw new IOException("RUN90 invalid authored location metadata",invalid);}
        }
        return schema;
    }
    private static void requireSparseCompoundList(CompoundTag data,String name)throws IOException {
        if(!(data.get(name) instanceof ListTag values)
                ||!values.isEmpty()&&values.getElementType()!=Tag.TAG_COMPOUND)
            throw new IOException("RUN90 missing/invalid parent metadata list "+name);
    }

    public static Capture externalizeRun90Delta(CompoundTag captured,Path target,String store)
            throws IOException {
        if(!"proutlost_worldprep".equals(store))
            throw new IOException("RUN90 sparse writer restricted to parent WorldPrep store");
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target))
            throw new IOException("RUN90 requires prior exact native checkpoint file");
        if(!captured.contains("data",Tag.TAG_COMPOUND))
            throw new IOException("RUN90 captured envelope missing data");
        var capturedData=captured.getCompound("data");
        if(!capturedData.contains("__run90_incremental_v1",Tag.TAG_COMPOUND))
            throw new IOException("RUN90 missing bounded delta intent");
        var intent=capturedData.getCompound("__run90_incremental_v1");
        if(intent.getInt("version")!=1||!store.equals(intent.getString("store")))
            throw new IOException("RUN90 unsupported sparse intent schema/store");
        int capturedSchema=requireSparseInlineMetadata(capturedData);
        if(intent.getInt("areasCount")<0||intent.getInt("jobsCount")<0
                ||intent.getInt("areasCount")>MAX_ENTRY_COUNT
                ||intent.getInt("jobsCount")>MAX_ENTRY_COUNT)
            throw new IOException("RUN90 excessive total record count");
        var previous=NbtIo.readCompressed(target,NbtAccounter.unlimitedHeap());
        if(!isPaged(previous))
            throw new IOException("RUN90 refuses legacy or absent prior compact root");
        var previousData=previous.getCompound("data");
        int previousSchema=requireSparseInlineMetadata(previousData);
        if(capturedSchema<previousSchema)
            throw new IOException("RUN90 refuses a parent metadata schema downgrade");
        var prev=previousData.getCompound(MARKER);
        if(prev.getInt("version")!=VERSION||prev.getInt("bucketCount")!=BUCKETS
                ||!store.equals(prev.getString("store")))
            throw new IOException("RUN90 prior immutable paged root identity mismatch");
        var newRoot=previous.copy();
        var data=newRoot.getCompound("data");
        // Full-capture parity for every inline field, including authored
        // locations/analyses and future non-paged metadata. Existing immutable
        // paged sections remain referenced until the same atomic root publish.
        for(var name:capturedData.getAllKeys())
            if(!NAMES.contains(name)&&!name.equals("__run90_incremental_v1")&&!name.equals(MARKER))
                data.put(name,capturedData.get(name).copy());
        var index=data.getCompound(MARKER);
        var sections=index.getList("sections",Tag.TAG_COMPOUND);
        var seenSections=new HashSet<String>();
        var changedPages=new ArrayList<PendingPage>();
        int pageRefs=0;
        long pageBytes=0L;
        int allNewPages=0;
        var present=new HashSet<String>();
        for(var rawSection:sections){
            var section=(CompoundTag)rawSection;
            String sectionName=section.getString("name");
            if(!NAMES.contains(sectionName)||!seenSections.add(sectionName))
                throw new IOException("RUN90 duplicate/unknown parent paged section");
            if(!sectionName.equals("areas")&&!sectionName.equals("jobs")){
                if(section.getInt("count")!=0)
                    throw new IOException("RUN90 non-empty unsupported section "+sectionName);
                continue;
            }
            present.add(sectionName);
            var patches=intent.getList(sectionName,Tag.TAG_COMPOUND);
            int expected=intent.getInt(sectionName+"Count");
            int initialCount=section.getInt("count");
            if(initialCount<0||initialCount>MAX_ENTRY_COUNT)
                throw new IOException("RUN90 invalid previous section count");
            var oldRefs=section.getList("buckets",Tag.TAG_COMPOUND);
            var refs=new java.util.TreeMap<Integer,CompoundTag>();
            var dirty=new java.util.TreeMap<Integer,java.util.LinkedHashMap<String,CompoundTag>>();
            var seenKeys=new HashSet<String>();
            for(var rawRef:oldRefs){
                var ref=(CompoundTag)rawRef;
                int bucket=ref.getInt("bucket");
                String digest=ref.getString("sha256");
                if(bucket<0||bucket>=BUCKETS||refs.putIfAbsent(bucket,ref.copy())!=null)
                    throw new IOException("RUN90 duplicate/bad bucket ref");
                if(!Boolean.getBoolean("proutlost.run116.parallelNbtPageChecks")){
                    Path path=pagePath(target,store,sectionName,digest);
                    var previousStat=stat(path);
                    if(!previousStat.equals(VERIFIED_PAGES.get(path)))readPage(path,digest);
                }
                pageRefs++;
            }
            if(Boolean.getBoolean("proutlost.run116.parallelNbtPageChecks"))
                verifyRun116References(target,store,sectionName,refs);
            for(var rawPatch:patches){
                var patch=(CompoundTag)rawPatch;
                String key=keyFor(store,sectionName,patch);
                if(!seenKeys.add(key))throw new IOException("RUN90 duplicate patch key "+key);
                int bucket=partition(key);
                dirty.computeIfAbsent(bucket,k->new java.util.LinkedHashMap<>()).put(key,patch);
            }
            int nextOrder=initialCount;
            var oldKeysInDirty=new HashSet<String>();
            var bucketRecords=new java.util.HashMap<Integer,java.util.LinkedHashMap<String,CompoundTag>>();
            for(var group:dirty.entrySet()){
                int bucket=group.getKey();
                var map=new java.util.LinkedHashMap<String,CompoundTag>();
                var oldRef=refs.get(bucket);
                if(oldRef!=null){
                    var page=readPage(pagePath(target,store,sectionName,
                                oldRef.getString("sha256")),oldRef.getString("sha256"));
                    if(page.getInt("version")!=VERSION||
                            !store.equals(page.getString("store"))||
                            !sectionName.equals(page.getString("section"))||
                            page.getInt("bucket")!=bucket)
                        throw new IOException("RUN90 foreign/malformed old page");
                    var oldEntries=page.getList("entries",Tag.TAG_COMPOUND);
                    if(oldEntries.size()!=oldRef.getInt("count"))
                        throw new IOException("RUN90 referenced old page count mismatch");
                    for(var rawEntry:oldEntries){
                        var entry=(CompoundTag)rawEntry;
                        var k=entry.getString("key");
                        if(!k.equals(keyFor(store,sectionName,entry.getCompound("record")))
                                ||partition(k)!=bucket||map.putIfAbsent(k,entry.copy())!=null)
                            throw new IOException("RUN90 old page identity mismatch");
                    }
                }
                bucketRecords.put(bucket,map);
            }
            // Assign global ordinal in supplied ordered delta order, NOT sorted
            // bucket order. Existing parent insertion ordinal must be retained.
            for(var rawPatch:patches){
                var patch=(CompoundTag)rawPatch;
                String key=keyFor(store,sectionName,patch);
                int bucket=partition(key);
                var group=bucketRecords.get(bucket);
                var old=group.get(key);
                int ordinal;
                if(old!=null){
                    ordinal=old.getInt("order");
                    if(ordinal<0||ordinal>=initialCount)
                        throw new IOException("RUN90 existing ordinal invalid");
                    oldKeysInDirty.add(key);
                }else{
                    ordinal=nextOrder++;
                }
                var entry=new CompoundTag();
                entry.putString("key",key);entry.putInt("order",ordinal);
                entry.put("record",patch.copy());
                group.put(key,entry);
            }
            if(nextOrder!=expected || expected<initialCount)
                throw new IOException("RUN90 count mismatch; possible deletion or omission");
            for(var group:bucketRecords.entrySet()){
                int bucket=group.getKey();
                var records=new ArrayList<CompoundTag>(group.getValue().values());
                records.sort(Comparator.comparingInt(x->x.getInt("order")));
                var entries=new ListTag();
                int previousOrdinal=-1;
                for(var rec:records){
                    if(rec.getInt("order")<=previousOrdinal)
                        throw new IOException("RUN90 duplicate/out-of-order global record ordinal");
                    previousOrdinal=rec.getInt("order");
                    entries.add(rec);
                }
                var page=new CompoundTag();
                page.putInt("version",VERSION);
                page.putString("store",store);
                page.putString("section",sectionName);
                page.putInt("bucket",bucket);
                page.put("entries",entries);
                var bytes=serialize(page);
                if(bytes.length>MAX_PAGE_UNCOMPRESSED)
                    throw new IOException("RUN90 modified page exceeds strict limit");
                String digest=digest(bytes);
                var destination=pagePath(target,store,sectionName,digest);
                var staged=stagePage(destination,bytes,digest);
                if(staged!=null){
                    changedPages.add(staged);
                    allNewPages++;pageBytes+=Files.size(staged.temp());
                }
                var ref=new CompoundTag();
                ref.putInt("bucket",bucket);
                ref.putString("sha256",digest);
                ref.putInt("count",records.size());
                if(refs.put(bucket,ref)==null)pageRefs++;
            }
            section.putInt("count",expected);
            var orderedRefs=new ListTag();
            long sum=0;
            for(var ref:refs.values()){sum+=ref.getInt("count");orderedRefs.add(ref);}
            if(sum!=expected)throw new IOException("RUN90 section count not fully covered");
            section.put("buckets",orderedRefs);
        }
        if(!present.equals(Set.of("areas","jobs")))
            throw new IOException("RUN90 missing exact ordered sections");
        // No root publication until all new pages are durably SHA-addressed.
        commitStagedPages(changedPages,target);
        return new Capture(newRoot,
            new Metrics(pageRefs,allNewPages,pageBytes,serialize(newRoot).length));
    }

    /** RUN52 DEV opt-in. Shared bounded queue: at most four workers execute
     * only on already-captured immutable NBT. No world, receipts, journals,
     * root commits or Minecraft API calls are ever touched by these threads.
     */
    private record ParallelEncodedBucket(int bucket,String sha,int count,
                                         PendingPage staged,CachedPage cache){}
    private static final java.util.concurrent.atomic.AtomicInteger RUN52_THREAD_NUMBER=
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ExecutorService RUN52_CPU_POOL=
            new java.util.concurrent.ThreadPoolExecutor(4,4,0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(16),
                task -> {
                    var thread=new Thread(task,"worldprep-run52-nbt-cpu-"+
                        RUN52_THREAD_NUMBER.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY+1);
                    return thread;
                },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static List<ParallelEncodedBucket> parallelBucketEncode(
            Path target,String store,String section,List<Record>[] buckets)throws IOException {
        var futures=new ArrayList<java.util.concurrent.CompletableFuture<List<ParallelEncodedBucket>>>(4);
        for(int start=0;start<BUCKETS;start+=BUCKETS/4){
            final int from=start,to=Math.min(BUCKETS,start+BUCKETS/4);
            try{
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(()->{
                    var out=new ArrayList<ParallelEncodedBucket>();
                    try {
                        for(int bucket=from;bucket<to;bucket++){
                            var source=buckets[bucket];
                            if(source.isEmpty())continue;
                            var key=new BucketKey(target.toAbsolutePath().normalize(),store,section,bucket);
                            var cached=BUCKET_CACHE.get(key);
                            String digest;
                            PendingPage pending=null;
                            CachedPage updated=null;
                            if(cached!=null&&cached.immutableRecords().equals(source)){
                                digest=cached.sha();
                                Path existing=pagePath(target,store,section,digest);
                                if(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))
                                    throw new IOException("Missing unchanged RUN52 page");
                                if(!stat(existing).equals(VERIFIED_PAGES.get(existing)))
                                    readPage(existing,digest);
                            }else{
                                var page=new CompoundTag();
                                page.putInt("version",VERSION);
                                page.putString("store",store);
                                page.putString("section",section);
                                page.putInt("bucket",bucket);
                                var entries=new ListTag();
                                for(var record:source){
                                    var wrapper=new CompoundTag();
                                    wrapper.putString("key",record.key());
                                    if(preserveInsertionOrder(store,section))
                                        wrapper.putInt("order",record.order());
                                    wrapper.put("record",record.payload());
                                    entries.add(wrapper);
                                }
                                page.put("entries",entries);
                                byte[] raw=serialize(page);
                                if(raw.length>MAX_PAGE_UNCOMPRESSED)
                                    throw new IOException("RUN52 page exceeds existing hard cap");
                                digest=digest(raw);
                                pending=stagePage(pagePath(target,store,section,digest),raw,digest);
                                var frozen=new ArrayList<Record>(source.size());
                                for(var r:source)
                                    frozen.add(new Record(r.key(),r.order(),r.payload().copy()));
                                updated=new CachedPage(digest,List.copyOf(frozen));
                            }
                            out.add(new ParallelEncodedBucket(bucket,digest,source.size(),pending,updated));
                        }
                        return List.copyOf(out);
                    }catch(IOException problem){throw new java.io.UncheckedIOException(problem);}
                },RUN52_CPU_POOL));
            }catch(java.util.concurrent.RejectedExecutionException e){
                // A rejected bounded queue is NEVER an authority to fall back
                // to unsafe processing or to publish partial page roots.
                for(var f:futures)f.join();
                throw new IOException("RUN52 bounded pool saturated; no root published",e);
            }
        }
        try{
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        }catch(java.util.concurrent.CompletionException e){
            var reason=e.getCause();
            if(reason instanceof java.io.UncheckedIOException io)throw io.getCause();
            throw new IOException("RUN52 parallel captured-page encoding failed",reason);
        }
        var merged=new ArrayList<ParallelEncodedBucket>();
        for(var future:futures)merged.addAll(future.join());
        int prior=-1;
        for(var result:merged){
            if(result.bucket()<=prior)
                throw new IOException("RUN52 non-canonical parallel bucket merge");
            prior=result.bucket();
        }
        return List.copyOf(merged);
    }

    public static boolean isPaged(CompoundTag envelope){
        return envelope.contains("data",Tag.TAG_COMPOUND)
                && envelope.getCompound("data").contains(MARKER,Tag.TAG_COMPOUND);
    }

    public static CompoundTag expand(CompoundTag envelope,Path legacyTarget,String store) throws IOException {
        if(!STORES.contains(store))throw new IOException("Unknown metadata store");
        if(!isPaged(envelope))return envelope.copy();
        var complete=envelope.copy();
        var data=complete.getCompound("data");
        var index=data.getCompound(MARKER);
        if(index.getInt("version")!=VERSION || index.getInt("bucketCount")!=BUCKETS
                ||!store.equals(index.getString("store")))
            throw new IOException("Incompatible paged metadata manifest; no partial recovery");
        var sections=index.getList("sections",Tag.TAG_COMPOUND);
        if(sections.isEmpty()||sections.size()>NAMES.size())
            throw new IOException("Empty or excessive paged metadata sections");
        Set<String> seenSections=new HashSet<>();
        for(var secTag:sections){
            var sec=(CompoundTag)secTag;
            String name=sec.getString("name");
            if(!NAMES.contains(name) || !seenSections.add(name) || data.contains(name))
                throw new IOException("Section identity duplicated, foreign or inline conflict");
            int count=sec.getInt("count");
            if(count<0||count>MAX_ENTRY_COUNT)throw new IOException("Section count outside safety bounds");
            boolean ordered=preserveInsertionOrder(store,name);
            if(sec.getBoolean("ordered")!=ordered)throw new IOException("Ordered section mode mismatch "+name);
            var refs=sec.getList("buckets",Tag.TAG_COMPOUND);
            if(refs.size()>BUCKETS)throw new IOException("Too many bucket references");
            var records=new ArrayList<Record>(count);
            Set<Integer> seenBuckets=new HashSet<>();
            Set<String> seenKeys=new HashSet<>();
            int total=0;
            for(var refTag:refs){
                var ref=(CompoundTag)refTag;
                int b=ref.getInt("bucket");
                String sha=ref.getString("sha256");
                int n=ref.getInt("count");
                if(b<0||b>=BUCKETS || !seenBuckets.add(b) || n<=0||n>MAX_ENTRY_COUNT
                        ||!sha.matches("[0-9a-f]{64}"))
                    throw new IOException("Malformed bucket index for "+name);
                var page=readPage(pagePath(legacyTarget,store,name,sha),sha);
                if(page.getInt("version")!=VERSION || !store.equals(page.getString("store"))
                        ||!name.equals(page.getString("section")) || page.getInt("bucket")!=b)
                    throw new IOException("Wrong immutable bucket identity for "+name);
                var items=page.getList("entries",Tag.TAG_COMPOUND);
                if(items.size()!=n)throw new IOException("Bucket record-count mismatch "+name);
                for(var entry:items){
                    var w=(CompoundTag)entry;
                    if(!w.contains("record",Tag.TAG_COMPOUND))throw new IOException("Missing bucket record");
                    var rec=w.getCompound("record");
                    String key=keyFor(store,name,rec);
                    if(!key.equals(w.getString("key")) || partition(key)!=b || !seenKeys.add(key))
                        throw new IOException("Bucket record key, hash, or duplicate conflict");
                    int order=ordered?w.getInt("order"):-1;
                    records.add(new Record(key,order,rec));
                }
                total+=n;
            }
            if(total!=count || records.size()!=count)
                throw new IOException("Paged records missing or counted more than once in "+name);
            if(ordered){
                records.sort(Comparator.comparingInt(Record::order));
                for(int i=0;i<records.size();i++)
                    if(records.get(i).order()!=i)
                        throw new IOException("Insertion order record missing/duplicated");
            }else if(name.equals("plans")){
                records.sort(Comparator.comparing(Record::key));
            }else if(name.equals("journals")||name.equals("snapshots")){
                records.sort((a,b)->UUID.fromString(a.key()).compareTo(UUID.fromString(b.key())));
            }else{
                throw new IOException("Unknown unordered section policy");
            }
            var restored=new ListTag();
            for(var record:records)restored.add(record.payload().copy());
            data.put(name,restored);
        }
        data.remove(MARKER);
        return complete;
    }

    private static String keyFor(String store,String section,CompoundTag rec) throws IOException {
        return switch(section){
            case "areas" -> requiredString(rec,"id");
            case "jobs","journals","snapshots" -> {
                if(!rec.hasUUID("id"))throw new IOException("Missing immutable UUID "+section);
                yield rec.getUUID("id").toString();
            }
            case "plans" -> {
                String area=requiredString(rec,"area");
                if(store.equals("proutlost_worldprep_blocks"))
                    yield area+"|"+requiredString(rec,"pass");
                yield area;
            }
            default -> throw new IOException("Unexpected bucket key section");
        };
    }
    private static String requiredString(CompoundTag tag,String field) throws IOException {
        if(!tag.contains(field,Tag.TAG_STRING)||tag.getString(field).isBlank())
            throw new IOException("No stable record identity "+field);
        return tag.getString(field);
    }
    private static boolean preserveInsertionOrder(String store,String section){
        return store.equals("proutlost_worldprep")
                && (section.equals("areas")||section.equals("jobs"));
    }
    private static int partition(String key) throws IOException {
        Integer known=PARTITION_CACHE.get(key);
        if(known!=null)return known;
        byte[] hash=hash(key.getBytes(StandardCharsets.UTF_8));
        int computed=(((hash[0]&255)<<8)|(hash[1]&255))%BUCKETS;
        PARTITION_CACHE.putIfAbsent(key,computed);
        return computed;
    }
    private static Path pagePath(Path legacy,String store,String section,String sha) throws IOException {
        if(!STORES.contains(store) || !NAMES.contains(section)||!sha.matches("[0-9a-f]{64}"))
            throw new IOException("Unsafe paged metadata path arguments");
        return legacy.toAbsolutePath().getParent().resolve("worldprep-paged-v1")
                .resolve(store).resolve(section).resolve(sha+".nbt.gz");
    }
    private static byte[] serialize(CompoundTag tag) throws IOException{
        try(var out=new ByteArrayOutputStream();
            var data=new DataOutputStream(out)){
            NbtIo.write(tag,data);
            data.flush();
            return out.toByteArray();
        }
    }
    public static String fileSha256(Path file)throws IOException{
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing/linked SHA-pinned evidence");
        try(var input=Files.newInputStream(file)){
            var h=MessageDigest.getInstance("SHA-256");
            byte[] bytes=new byte[1024*1024];
            for(int n;(n=input.read(bytes))!=-1;)h.update(bytes,0,n);
            return HexFormat.of().formatHex(h.digest());
        }catch(java.security.NoSuchAlgorithmException e){
            throw new IOException("SHA-256 unavailable",e);
        }
    }
    private static byte[] hash(byte[] input)throws IOException{
        try{return MessageDigest.getInstance("SHA-256").digest(input);}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException("SHA-256 unavailable",e);}
    }
    private static String digest(byte[] input)throws IOException{return HexFormat.of().formatHex(hash(input));}
    private static PendingPage stagePage(Path dest,byte[] original,String sha)throws IOException{
        var directory=dest.getParent();
        Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(directory.getParent())
                ||Files.isSymbolicLink(directory.getParent().getParent()))
            throw new IOException("Symlinked bucket path");
        if(Files.exists(dest,LinkOption.NOFOLLOW_LINKS)){
            var current=stat(dest);
            if(!current.equals(VERIFIED_PAGES.get(dest)))readPage(dest,sha);
            return null;
        }
        var tmp=Files.createTempFile(directory,".worldprep-page-",".partial");
        // At this point NO root points to the staged page, and no published
        // final name exists. A crash here cannot invalidate the old checkpoint.
        try(var out=Files.newOutputStream(tmp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);
            var buffered=new java.io.BufferedOutputStream(out,128*1024);
            var gzip=new GZIPOutputStream(buffered,128*1024){{def.setLevel(1);}}){
            gzip.write(original);
        }
        return new PendingPage(tmp,dest,sha);
    }
    private static void commitStagedPages(List<PendingPage> pending,Path target)throws IOException{
        if(pending.isEmpty())return;
        // Small updates: force <=8 new content files, then their unique
        // directories. Bulk migration: two Linux syncfs calls instead of
        // thousands of individual HDD fsyncs. BOTH policies are durable
        // before the caller is allowed to publish the compact root index.
        boolean small=pending.size()<=8;
        if(small){
            for(var staged:pending){
                try(var c=FileChannel.open(staged.temp(),StandardOpenOption.WRITE)){c.force(true);}
            }
        }else syncFilesystem(target.toAbsolutePath().getParent());
        var changedDirectories=new HashSet<Path>();
        for(var staged:pending){
            Path dest=staged.finalPath();
            try{
                Files.createLink(dest,staged.temp()); // atomic CREATE_NEW: never overwrite
                VERIFIED_PAGES.put(dest,stat(dest));
            }catch(FileAlreadyExistsException concurrent){
                readPage(dest,staged.sha()); // conflicting hash page must match
            }
            Files.delete(staged.temp());
            changedDirectories.add(dest.getParent());
        }
        if(small){
            for(var directory:changedDirectories)forceDirectory(directory);
        }else syncFilesystem(target.toAbsolutePath().getParent());
    }
    private static void syncFilesystem(Path directory)throws IOException{
        if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("linux"))
            throw new IOException("RUN20 batched syncfs requires Linux; refuse unsafe fallback");
        Process child=new ProcessBuilder("/usr/bin/sync","-f",directory.toString())
                .redirectErrorStream(true).start();
        try{
            if(!child.waitFor(120,java.util.concurrent.TimeUnit.SECONDS)){
                child.destroyForcibly();
                throw new IOException("Batch syncfs timeout; root not published");
            }
            if(child.exitValue()!=0)
                throw new IOException("Batch syncfs failed exit "+child.exitValue());
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IOException("Batch syncfs interrupted; root not published",e);
        }
    }
    private static CompoundTag readPage(Path dest,String expectedSha)throws IOException{
        if(Files.isSymbolicLink(dest)||!Files.isRegularFile(dest,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(dest)>MAX_PAGE_COMPRESSED)
            throw new IOException("Missing, linked or oversized checkpoint page "+dest.getFileName());
        byte[] uncomp;
        try(var in=new java.util.zip.GZIPInputStream(Files.newInputStream(dest),128*1024);
            var out=new ByteArrayOutputStream()){
            byte[] buf=new byte[128*1024];
            for(int n;(n=in.read(buf))!=-1;){
                if(out.size()+n>MAX_PAGE_UNCOMPRESSED)
                    throw new IOException("Paged checkpoint decompression limit");
                out.write(buf,0,n);
            }
            uncomp=out.toByteArray();
        }
        if(!expectedSha.equals(digest(uncomp)))throw new IOException("Immutable checkpoint page SHA mismatch");
        try{
            var verified=NbtIo.readCompressed(dest,NbtAccounter.create(MAX_PAGE_UNCOMPRESSED));
            VERIFIED_PAGES.put(dest,stat(dest));
            return verified;
        }catch(Exception bad){
            throw new IOException("Malformed checkpoint page NBT",bad);
        }
    }
    private static void forceDirectory(Path directory)throws IOException{
        try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
    }
    /**
     * Change-only block metadata with the existing immutable-page wire format.
     * Every referenced old page receives the SAME stat/SHA validation as the
     * parent delta writer. Dirty buckets are fully SHA decoded before editing.
     * No root is published here; the existing durable atomic publisher owns ACK.
     */
    public static Capture externalizeBlockDelta(CompoundTag captured,Path target,String store)
            throws IOException {
        if(!BlockWorldPrepData.FILE_ID.equals(store))
            throw new IOException("Block delta restricted to block metadata store");
        if(Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Block delta requires existing exact checkpoint");
        var in=captured.getCompound("data");
        if(in.getInt("format")!=BlockWorldPrepData.FORMAT_VERSION
                ||!in.contains(BlockWorldPrepData.BLOCK_DELTA_MARKER,Tag.TAG_COMPOUND))
            throw new IOException("Missing canonical block delta format");
        var intent=in.getCompound(BlockWorldPrepData.BLOCK_DELTA_MARKER);
        if(intent.getInt("version")!=1||!store.equals(intent.getString("store")))
            throw new IOException("Foreign block delta identity");
        var previous=NbtIo.readCompressed(target,NbtAccounter.create(16L*1024*1024));
        if(!isPaged(previous)||previous.getCompound("data").getInt("format")!=BlockWorldPrepData.FORMAT_VERSION)
            throw new IOException("Block delta refuses absent/legacy/incompatible root");
        var root=previous.copy();
        var data=root.getCompound("data");
        var index=data.getCompound(MARKER);
        if(index.getInt("version")!=VERSION||index.getInt("bucketCount")!=BUCKETS
                ||!store.equals(index.getString("store")))
            throw new IOException("Block delta prior root identity mismatch");
        var seenSections=new HashSet<String>();
        var pending=new ArrayList<PendingPage>();
        int pageRefs=0,newPages=0;long pageBytes=0;
        for(var value:index.getList("sections",Tag.TAG_COMPOUND)) {
            var section=(CompoundTag)value;
            String name=section.getString("name");
            if(!Set.of("plans","journals").contains(name)||!seenSections.add(name)
                    ||section.getBoolean("ordered")||data.contains(name))
                throw new IOException("Block delta unknown/duplicate/ordered/inline section");
            int oldCount=section.getInt("count"),expected=intent.getInt(name+"Count");
            if(!intent.contains(name+"Count",Tag.TAG_INT)||oldCount<0||oldCount>MAX_ENTRY_COUNT
                    ||expected<0||expected>MAX_ENTRY_COUNT
                    ||!intent.contains(name,Tag.TAG_LIST)||!intent.contains(name+"Deleted",Tag.TAG_LIST))
                throw new IOException("Block delta missing or excessive count/patches");
            var refs=new java.util.TreeMap<Integer,CompoundTag>();
            long total=0;
            for(var raw:section.getList("buckets",Tag.TAG_COMPOUND)) {
                var ref=(CompoundTag)raw;
                int bucket=ref.getInt("bucket"),count=ref.getInt("count");
                String sha=ref.getString("sha256");
                if(bucket<0||bucket>=BUCKETS||count<=0||count>MAX_ENTRY_COUNT
                        ||!sha.matches("[0-9a-f]{64}")||refs.putIfAbsent(bucket,ref.copy())!=null)
                    throw new IOException("Block delta malformed old reference");
                total+=count;
                if(!Boolean.getBoolean("proutlost.run116.parallelNbtPageChecks")) {
                    var path=pagePath(target,store,name,sha);
                    if(!stat(path).equals(VERIFIED_PAGES.get(path)))readPage(path,sha);
                }
            }
            if(total!=oldCount)throw new IOException("Block delta prior count mismatch");
            if(Boolean.getBoolean("proutlost.run116.parallelNbtPageChecks"))
                verifyRun116References(target,store,name,refs);
            var patchList=(ListTag)intent.get(name);
            var deleteList=(ListTag)intent.get(name+"Deleted");
            if((!patchList.isEmpty()&&patchList.getElementType()!=Tag.TAG_COMPOUND)
                    ||(!deleteList.isEmpty()&&deleteList.getElementType()!=Tag.TAG_STRING)
                    ||patchList.size()>MAX_ENTRY_COUNT||deleteList.size()>MAX_ENTRY_COUNT)
                throw new IOException("Block delta noncanonical patch/deletion list");
            var changes=new java.util.LinkedHashMap<String,CompoundTag>();
            for(var raw:intent.getList(name,Tag.TAG_COMPOUND)) {
                var patch=(CompoundTag)raw;
                String key=keyFor(store,name,patch);
                if(changes.putIfAbsent(key,patch)!=null)
                    throw new IOException("Duplicate block delta patch");
            }
            var deleted=intent.getList(name+"Deleted",Tag.TAG_STRING);
            var keys=new java.util.LinkedHashSet<String>(changes.keySet());
            var deletions=new HashSet<String>();
            for(var raw:deleted) {
                String key=raw.getAsString();
                if(key.isBlank()||!deletions.add(key)||!keys.add(key))
                    throw new IOException("Duplicate/conflicting block deletion");
            }
            var dirty=new java.util.TreeMap<Integer,java.util.LinkedHashMap<String,CompoundTag>>();
            for(String key:keys)dirty.computeIfAbsent(partition(key),k->new java.util.LinkedHashMap<>());
            for(var group:dirty.entrySet()) {
                int bucket=group.getKey();
                var ref=refs.get(bucket);
                if(ref==null)continue;
                String sha=ref.getString("sha256");
                var page=readPage(pagePath(target,store,name,sha),sha);
                if(page.getInt("version")!=VERSION||!store.equals(page.getString("store"))
                        ||!name.equals(page.getString("section"))||page.getInt("bucket")!=bucket)
                    throw new IOException("Foreign block delta old page");
                var entries=page.getList("entries",Tag.TAG_COMPOUND);
                if(entries.size()!=ref.getInt("count"))throw new IOException("Block delta old page count");
                for(var raw:entries) {
                    var entry=(CompoundTag)raw;
                    String key=entry.getString("key");
                    if(!entry.contains("record",Tag.TAG_COMPOUND)||entry.contains("order")
                            ||!key.equals(keyFor(store,name,entry.getCompound("record")))
                            ||partition(key)!=bucket||group.getValue().putIfAbsent(key,entry.copy())!=null)
                        throw new IOException("Block delta old record ownership mismatch");
                }
            }
            int nextCount=oldCount;
            for(String key:deletions) {
                if(dirty.get(partition(key)).remove(key)==null)
                    throw new IOException("Block delta deletion of missing record");
                nextCount--;
            }
            for(var patch:changes.entrySet()) {
                var group=dirty.get(partition(patch.getKey()));
                var entry=new CompoundTag();
                entry.putString("key",patch.getKey());entry.put("record",patch.getValue().copy());
                if(group.put(patch.getKey(),entry)==null)nextCount++;
            }
            if(nextCount!=expected)throw new IOException("Block delta count mismatch/omitted mutations");
            for(var group:dirty.entrySet()) {
                int bucket=group.getKey();
                if(group.getValue().isEmpty()) { refs.remove(bucket);continue; }
                var records=new ArrayList<>(group.getValue().values());
                if(name.equals("journals"))
                    records.sort((a,b)->UUID.fromString(a.getString("key")).compareTo(UUID.fromString(b.getString("key"))));
                else records.sort(Comparator.comparing(a->a.getString("key")));
                var entries=new ListTag();records.forEach(entries::add);
                var page=new CompoundTag();
                page.putInt("version",VERSION);page.putString("store",store);
                page.putString("section",name);page.putInt("bucket",bucket);page.put("entries",entries);
                byte[] bytes=serialize(page);
                if(bytes.length>MAX_PAGE_UNCOMPRESSED)throw new IOException("Block delta page exceeds bound");
                String sha=digest(bytes);
                var staged=stagePage(pagePath(target,store,name,sha),bytes,sha);
                if(staged!=null) { pending.add(staged);newPages++;pageBytes+=Files.size(staged.temp()); }
                var ref=new CompoundTag();ref.putInt("bucket",bucket);
                ref.putString("sha256",sha);ref.putInt("count",records.size());refs.put(bucket,ref);
            }
            var references=new ListTag();total=0;
            for(var ref:refs.values()){ references.add(ref);total+=ref.getInt("count"); }
            if(total!=expected)throw new IOException("Block delta incomplete reference coverage");
            section.putInt("count",expected);section.put("buckets",references);pageRefs+=refs.size();
        }
        if(!seenSections.equals(Set.of("plans","journals")))
            throw new IOException("Block delta missing required sections");
        commitStagedPages(pending,target);
        return new Capture(root,new Metrics(pageRefs,newPages,pageBytes,serialize(root).length));
    }

    private ImmutableBucketedNbtCheckpoint(){}
}
