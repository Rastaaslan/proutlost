package fr.proutlost.worldprep.physical;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Small immutable write-ahead records. Caller owns the server-thread operation fence.
 * No receipt means no permission to invent completion. Failed publication poisons that call.
 */
public final class PhysicalStore {
    private static final Gson JSON = new Gson();
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path root;
    private final boolean readOnly;
    private record Envelope(int version,String sha256,String payload) {}
    public PhysicalStore(Path root) throws IOException {this(root,true);}
    /** Receipt readers never recreate a source directory that disappeared. */
    public static PhysicalStore openReadOnly(Path root)throws IOException {return new PhysicalStore(root,false);}
    private PhysicalStore(Path root,boolean create)throws IOException {
        this.root=root.toAbsolutePath().normalize();this.readOnly=!create;
        for(Path p=this.root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink store refused");
        if(create)Files.createDirectories(this.root);
        else if(!Files.isDirectory(this.root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Existing physical receipt directory required");
    }
    private Path path(String key) {
        if(!key.matches("[a-zA-Z0-9_-]{1,96}"))throw new IllegalArgumentException("Invalid record name");
        return root.resolve(key+".json");
    }
    public boolean exists(String key) {return Files.exists(path(key),LinkOption.NOFOLLOW_LINKS);}
    public <T> T read(String key,Class<T> type)throws IOException {
        try {return Objects.requireNonNull(JSON.fromJson(readText(key),type));}
        catch(RuntimeException e){throw new IOException("Invalid immutable record: "+key,e);}
    }
    public String readText(String key)throws IOException {
        var path=path(key);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>MAX_BYTES)throw new IOException("Missing/oversized record: "+key);
        try {
            var envelope=JSON.fromJson(Files.readString(path),Envelope.class);
            if(envelope==null||envelope.version()!=1||envelope.payload()==null||!sha(envelope.payload()).equals(envelope.sha256()))throw new IOException("Physical record checksum mismatch");
            return envelope.payload();
        } catch(RuntimeException failure){throw new IOException("Malformed physical envelope",failure);}
    }
    public String write(String key,Object value)throws IOException {
        if(readOnly)throw new IOException("Read-only physical receipt store");
        String text=JSON.toJson(value);
        byte[] bytes=JSON.toJson(new Envelope(1,sha(text),text)).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Record exceeds finite plan limit");
        if(exists(key)) {
            String retained=readText(key);
            // JSON object key ordering is not semantic and Map.of order varies across JVMs.
            // Preserve old bytes/digest: never rewrite retained receipts during recovery.
            if(!com.google.gson.JsonParser.parseString(retained).equals(com.google.gson.JsonParser.parseString(text)))
                throw new IOException("Immutable physical record conflict: "+key);
            sync(); return sha(retained);
        }
        Path temp=Files.createTempFile(root,".intent-",".partial");
        try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)) {
            var data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
        }
        Files.move(temp,path(key),StandardCopyOption.ATOMIC_MOVE);
        sync();return sha(text);
    }
    private void sync()throws IOException {try(var dir=FileChannel.open(root,StandardOpenOption.READ)){dir.force(true);}}
    public static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
