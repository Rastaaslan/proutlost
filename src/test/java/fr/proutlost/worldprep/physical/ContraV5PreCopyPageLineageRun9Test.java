package fr.proutlost.worldprep.physical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.InvocationTargetException;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Exact byte-level V2 paged backup metadata chain from native Python cold copier. */
class ContraV5PreCopyPageLineageRun9Test {
    @TempDir Path tmp;
    private static String sha(String s)throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
    private static String page(int i,String prev,String path){
        return "{\"version\":2,\"index\":"+i+",\"previousSha256\":\""+prev+
            "\",\"entries\":[{\"path\":\""+path+"\",\"bytes\":1,"+
            "\"sha256\":\""+("a".repeat(64))+"\",\"sourceModifiedMillis\":0,\"backupModifiedMillis\":0}]}";
    }
    private static void invokePages(Path src,Path dst,int count,String terminal)throws Exception {
        var m=ContraV5PreCopyBootstrapRun9.class.getDeclaredMethod(
            "pages",Path.class,Path.class,int.class,String.class);
        m.setAccessible(true);
        try {m.invoke(null,src,dst,count,terminal);}
        catch(InvocationTargetException e){
            if(e.getCause() instanceof Exception ex)throw ex;
            throw e;
        }
    }
    @Test void twoRealRawSHAChainedPagesCopyExactlyNoCanonicalRehash()throws Exception {
        var src=tmp.resolve("copy-a.json.pages");
        var dst=tmp.resolve("source-a.json.pages");
        Files.createDirectory(src);
        var first=page(0,"0".repeat(64),"level.dat");
        var second=page(1,sha(first),"worldprep-v2/batches/batch-uuid/00000000000000000001.json");
        Files.writeString(src.resolve("page-0.json"),first,StandardOpenOption.CREATE_NEW);
        Files.writeString(src.resolve("page-1.json"),second,StandardOpenOption.CREATE_NEW);
        invokePages(src,dst,2,sha(second));
        assertEquals(first,Files.readString(dst.resolve("page-0.json")));
        assertEquals(second,Files.readString(dst.resolve("page-1.json")));
    }
    @Test void forgedPreviousOrTerminalSHACannotPass()throws Exception {
        var src=tmp.resolve("copy-b.json.pages");
        Files.createDirectory(src);
        var first=page(0,"0".repeat(64),"level.dat");
        var forged=page(1,"0".repeat(64),"fake");
        Files.writeString(src.resolve("page-0.json"),first,StandardOpenOption.CREATE_NEW);
        Files.writeString(src.resolve("page-1.json"),forged,StandardOpenOption.CREATE_NEW);
        assertThrows(java.io.IOException.class,()->invokePages(src,tmp.resolve("dst-1"),2,sha(forged)));
        assertThrows(java.io.IOException.class,()->invokePages(src,tmp.resolve("dst-2"),3,sha(forged)));
    }
}
