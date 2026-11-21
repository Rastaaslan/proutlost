package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.codec.ModelCodec;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Dedicated IO executor only. Root is a server configuration path, never a client argument. */
public final class AdminAuditExporter {
    private final Path root;
    private final Executor writer;
    private final int maxBytes;
    public AdminAuditExporter(Path serverExportDirectory,Executor boundedWriter,int maxBytes) {
        root=Objects.requireNonNull(serverExportDirectory).toAbsolutePath().normalize();writer=Objects.requireNonNull(boundedWriter);
        if(maxBytes<1) throw new IllegalArgumentException("maxBytes");this.maxBytes=maxBytes;
    }
    public CompletionStage<Path> export(AuditDecision audit,BooleanSupplier authorizationStillValid) {
        Objects.requireNonNull(audit);Objects.requireNonNull(authorizationStillValid);
        var future=new CompletableFuture<Path>();
        try { writer.execute(()-> {
            try {
                if(!authorizationStillValid.getAsBoolean()) throw new SecurityException("AUTH_DENIED");
                byte[] bytes=new ModelCodec<>(AuditDecision.class).encode(audit);
                if(bytes.length>maxBytes) throw new IOException("Export exceeds configured byte limit");
                Files.createDirectories(root);
                if(!root.toRealPath().equals(root)) throw new IOException("Symlink export directory refused");
                Path destination=root.resolve(audit.decisionId().value()+"-"+UUID.randomUUID()+".json");
                if(!authorizationStillValid.getAsBoolean()) throw new SecurityException("AUTH_DENIED");
                try(var file=FileChannel.open(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) file.write(buffer);file.force(true);
                }
                future.complete(destination);
            } catch(Exception failure) {future.completeExceptionally(failure);}
        }); } catch(RejectedExecutionException full) {future.completeExceptionally(full);}
        return future.minimalCompletionStage();
    }
}
