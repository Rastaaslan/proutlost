package fr.proutlost.director.admin.client;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
/** One bounded client-main-thread document. Fragments are never exposed as a
 * complete record; a hash-verified typed section replaces them only at the end. */
public final class MemoryDocumentAssembler {
    private record Stamp(Revision revision,Tick tick,Sequence cut){}
    private MemoryDocumentPageData first;private AdminRow identity;private Stamp stamp;
    private ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    public void clear(){first=null;identity=null;stamp=null;bytes=new ByteArrayOutputStream();}
    public boolean pending(){return first!=null;}
    public Optional<AdminRow> accept(AdminSnapshot snapshot,AdminRow row){
        if(!(row.data() instanceof MemoryDocumentPageData page)){if(pending())throw new IllegalArgumentException("incomplete memory document replaced");return Optional.of(row);}
        var received=new Stamp(snapshot.snapshotRevision(),snapshot.capturedTick(),snapshot.lastDurableSequence());
        if(first==null){if(page.byteOffset()!=0)throw new IllegalArgumentException("memory document missing first fragment");first=page;identity=row;stamp=received;}
        else if(!stamp.equals(received)||!identity.scope().equals(row.scope())||!identity.revision().equals(row.revision())||!identity.capturedTick().equals(row.capturedTick())
            ||!first.documentHash().equals(page.documentHash())||first.documentBytes()!=page.documentBytes()||first.documentIndex()!=page.documentIndex()||first.documentCount()!=page.documentCount())
            throw new IllegalArgumentException("mixed memory document identity/cut");
        if(page.byteOffset()!=bytes.size())throw new IllegalArgumentException("memory document reordered/duplicate fragment");
        byte[] fragment=Base64.getDecoder().decode(page.bytesBase64());
        if(fragment.length>first.documentBytes()-bytes.size())throw new IllegalArgumentException("memory document allocation bound");bytes.writeBytes(fragment);
        if(bytes.size()!=first.documentBytes())return Optional.empty();
        byte[] complete=bytes.toByteArray();if(!BlobContent.hash(complete).equals(first.documentHash()))throw new IllegalArgumentException("memory document hash");
        var decoded=new ModelCodec<>(AdminRow.class).decode(complete);
        if(!decoded.scope().equals(identity.scope())||!decoded.revision().equals(identity.revision())||!decoded.capturedTick().equals(identity.capturedTick())
            ||!(decoded.data() instanceof RegionMemoryDetailData data)||!data.regionId().value().equals(decoded.scope().id()))throw new IllegalArgumentException("memory document semantic identity");
        clear();return Optional.of(decoded);
    }
}
