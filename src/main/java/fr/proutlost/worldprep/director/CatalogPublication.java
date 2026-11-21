package fr.proutlost.worldprep.director;

import fr.proutlost.contract.director.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Immutable raw contract wire artifact, bounded by the real codec's ceiling. */
public final class CatalogPublication {
    public static void write(Path file,String json)throws IOException {
        var snapshot=StrictCodec.decode(json,CatalogSnapshot.class);CatalogValidation.requireSealed(snapshot);
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);if(bytes.length>Limits.MAX_JSON_BYTES)throw new IOException("Catalogue exceeds wire ceiling");
        for(Path p=file.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink catalogue path");
        if(Files.exists(file)) {
            if(Files.size(file)>Limits.MAX_JSON_BYTES||!Files.readString(file).equals(json))throw new IOException("Immutable catalogue conflict");return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());var temp=Files.createTempFile(file.toAbsolutePath().getParent(),".catalog-",".partial");
        try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
        try(var dir=FileChannel.open(file.toAbsolutePath().getParent(),StandardOpenOption.READ)){dir.force(true);}
    }
    private CatalogPublication(){}
}
