package fr.proutlost.worldprep.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native segmented A/B census page traversal from actual RUN65 Python fixture. */
public class ContraV5SegmentedCensusPagesTest {
    @TempDir Path temp;
    private static final UUID BATCH=UUID.fromString("b1d627aa-4a9c-478b-9cc1-e65f2f37eb44");
    private static final Path RUN=Path.of("/srv/storage/proutlost-s2-candidates/contra-20261027/contra-run65-segmented-ab-prod-readiness-20261029/run65-final-sandbox-v4");
    private record Fixture(Path manifest,Path source,Path a,Path b) {}
    private Fixture fixture()throws IOException{
        Path bundle=RUN.resolve("physical-final-ab-"+BATCH);
        Path original=bundle.resolve("manifest-bundle");
        Path mine=temp.resolve("manifest-bundle");
        try(var files=Files.walk(original)){
            for(var path:files.toList()){
                Path dst=mine.resolve(original.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(dst);
                else Files.copy(path,dst);
            }
        }
        return new Fixture(mine.resolve("manifest.json"),RUN.resolve("fixture-world"),
            bundle.resolve("copy-A"),bundle.resolve("copy-B"));
    }
    private ContraV5SegmentedCensusPages open(Fixture f)throws IOException{
        return new ContraV5SegmentedCensusPages(f.manifest(),f.source(),f.a(),f.b(),BATCH);
    }
    @Test void readsEveryPythonProductionSegmentAndPinWithoutSkipping()throws Exception{
        var f=fixture();
        var reader=open(f);
        var names=new ArrayList<String>();
        for(int i=0;i<20;i++){
            var entry=reader.next();
            if(entry.isEmpty())break;
            assertEquals(f.a().toString(),entry.orElseThrow().header().backupRoot());
            assertEquals(entry.orElseThrow().first().path(),entry.orElseThrow().second().path());
            names.add(entry.orElseThrow().first().path());
        }
        assertEquals(List.of("nested/y.txt","worldprep-qualification-pin.json","x.txt"),names);
        assertEquals(3,reader.filesRead());
        assertTrue(reader.complete());
        assertTrue(reader.next().isEmpty());
    }
    @Test void failsClosedOnWrongUUID()throws Exception{
        var f=fixture();
        assertThrows(IOException.class,()->new ContraV5SegmentedCensusPages(
            f.manifest(),f.source(),f.a(),f.b(),
            UUID.fromString("5967f37c-c097-4faf-ac5d-e318fdc018bb")));
    }
    @Test void failsClosedOnChangedPageOrChain()throws Exception{
        var f=fixture();
        Path page=f.manifest().getParent()
            .resolve("segments/segment-01/backup-a.json.pages/page-0.json");
        Files.writeString(page,"{}");
        var cursor=open(f);
        assertThrows(IOException.class,()->{
            while(cursor.next().isPresent()){}
        });
        assertFalse(cursor.complete());
    }
    @Test void failsClosedOnMissingSegmentPage()throws Exception{
        var f=fixture();
        Files.delete(f.manifest().getParent()
            .resolve("segments/segment-01/backup-b.json.pages/page-0.json"));
        var cursor=open(f);
        assertThrows(IOException.class,()->{
            while(cursor.next().isPresent()){}
        });
        assertFalse(cursor.complete());
    }
    @Test void failsClosedOnOverlappingOrModifiedSegmentOrdinal()throws Exception{
        var f=fixture();
        String before=Files.readString(f.manifest());
        String after=before.replace("\"ordinal\":1","\"ordinal\":0");
        assertNotEquals(before,after);
        Files.writeString(f.manifest(),after);
        var cursor=open(f);
        assertThrows(IOException.class,()->{
            while(cursor.next().isPresent()){}
        });
    }
    @Test void failsClosedOnLateManifestMutationAfterOriginalPageRead()throws Exception{
        var f=fixture();
        var reader=open(f);
        assertTrue(reader.next().isPresent());
        Files.writeString(f.manifest(),Files.readString(f.manifest())+" ");
        assertThrows(IOException.class,()->{
            while(reader.next().isPresent()){}
        });
        assertFalse(reader.complete());
        assertThrows(IOException.class,reader::next);
    }
    @Test void failedCursorCannotBeResumedIntoFalseSuccess()throws Exception{
        var f=fixture();
        Path page=f.manifest().getParent()
            .resolve("segments/segment-00/backup-b.json.pages/page-0.json");
        Files.writeString(page,"{}");
        var reader=open(f);
        assertThrows(IOException.class,reader::next);
        assertThrows(IOException.class,reader::next);
        assertEquals(0,reader.filesRead());
        assertFalse(reader.complete());
    }
    @Test void rejectsMismatchedCopyRootEvenIfManifestItselfIsValid()throws Exception{
        var f=fixture();
        assertThrows(IOException.class,()->new ContraV5SegmentedCensusPages(
            f.manifest(),f.source(),f.b(),f.a(),BATCH));
    }
    @Test void failsClosedIfAnyGenerationPinChanges()throws Exception{
        var f=fixture();
        Path manifest=f.manifest();
        var text=Files.readString(manifest);
        String changed=text.replace("\"pinSha256\":\"","\"pinSha256\":\"0");
        assertNotEquals(text,changed);
        Files.writeString(manifest,changed);
        assertThrows(IOException.class,()->open(f));
    }
}
