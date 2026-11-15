package fr.proutlost.director.client.perception.g3;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** TEST_ONLY single bounded IO writer. No filesystem work occurs in submit/capture callers. */
public final class G3EvidenceWriter {
    public static final int MAX_PIXELS=3840*2160;
    private final Path directory;
    private final ThreadPoolExecutor worker;
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final Gson json=new Gson();
    public G3EvidenceWriter(Path jobOutput,UUID runId,String slot){
        if(!Set.of("A","B").contains(slot))throw new IllegalArgumentException("fixed A/B slot required");
        directory=jobOutput.resolve(runId.toString()).resolve(slot);
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"J06-G3-evidence-IO");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Optional<Throwable> failure(){return Optional.ofNullable(failure.get());}
    private CompletableFuture<Void> submit(Callable<Void> action){
        var result=new CompletableFuture<Void>();
        if(failure.get()!=null){result.completeExceptionally(failure.get());return result;}
        try{worker.execute(()->{try{action.call();result.complete(null);}catch(Throwable e){failure.compareAndSet(null,e);result.completeExceptionally(e);}});}
        catch(RejectedExecutionException e){failure.compareAndSet(null,e);result.completeExceptionally(e);}
        return result;
    }
    private void append(String line)throws Exception{
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("observations.jsonl"),line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private String encode(Map<String,Object> row){String line=json.toJson(row);if(line.length()>65536)throw new IllegalArgumentException("evidence row bound");return line;}
    public CompletionStage<Void> event(Map<String,Object> immutableRow){
        String line=encode(immutableRow);return submit(()->{append(line);return null;});
    }
    /** Ownership of copiedPixels transfers to this writer; caller must not mutate it. */
    public CompletionStage<Void> capture(Map<String,Object> immutableRow,UUID captureId,int width,int height,int[] copiedPixels){
        if(width<=0||height<=0||(long)width*height>MAX_PIXELS||copiedPixels.length!=(long)width*height)throw new IllegalArgumentException("frame bounds");
        // Freeze metadata before crossing threads. Pixels already copied out of NativeImage on render thread.
        String metadata=encode(immutableRow);
        return submit(()->{
            Files.createDirectories(directory);
            int[] argb=new int[copiedPixels.length];
            for(int i=0;i<argb.length;i++){int p=copiedPixels[i];argb[i]=(p&0xff00ff00)|((p&255)<<16)|((p>>>16)&255);}
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,argb,0,width);
            String filename=captureId+".png";Path path=directory.resolve(filename);
            try(var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW);var png=new javax.imageio.stream.MemoryCacheImageOutputStream(out)){if(!ImageIO.write(image,"PNG",png))throw new IllegalStateException("PNG encoder missing");}
            var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}
            var row=com.google.gson.JsonParser.parseString(metadata).getAsJsonObject();
            row.addProperty("png",filename);row.addProperty("png_sha256",HexFormat.of().formatHex(digest.digest()));
            row.addProperty("width",width);row.addProperty("height",height);append(json.toJson(row));return null;
        });
    }
    /** Does not wait. Runner awaits only off Minecraft threads before accepting the evidence directory. */
    public CompletionStage<Void> finish(){var result=submit(()->{if(failure.get()!=null)throw new IllegalStateException("incomplete evidence",failure.get());return null;});worker.shutdown();return result;}
}
