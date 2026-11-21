package fr.proutlost.worldprep.orchestration;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** Two stable receipt namespaces. Never migrate or reset ambiguous pre-.15 journals. */
public record BackupAuditPair(PagedBackupVerifier first,PagedBackupVerifier second) implements AutoCloseable {
    public static void requireNoLegacy(Path directory)throws IOException {
        for(String name:new String[]{"backup-a-cursor.json","backup-b-cursor.json","backup-page-receipts"})
            if(Files.exists(directory.resolve(name),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Legacy .14 backup audit layout retained; explicit offline recovery required, no reset: "+name);
    }
    public static BackupAuditPair open(PagedBackupVerifier.Header a,PagedBackupVerifier.Header b,
            Path world,Path pagesA,Path pagesB,Path directory,Set<Path> exclusions)throws IOException {
        requireNoLegacy(directory);
        // Stable role paths, not derived from a changeable header: verifier identity rejects substitution.
        var first=new PagedBackupVerifier(a,world,pagesA,directory.resolve("backup-a/cursor.json"),exclusions);
        try{return new BackupAuditPair(first,new PagedBackupVerifier(b,world,pagesB,directory.resolve("backup-b/cursor.json"),exclusions));}
        catch(IOException|RuntimeException e){first.close();throw e;}
    }
    public void close(){try{first.close();}finally{second.close();}}
}
