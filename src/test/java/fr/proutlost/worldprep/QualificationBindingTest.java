package fr.proutlost.worldprep;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class QualificationBindingTest {
    record Point(int x,int y,int z){}
    record Input(int version,UUID batch,String backupManifest,String backupSha256,String backupRootA,String backupRootB,Point origin){}
    @TempDir Path world;
    @Test void strictTypedInputRejectsNestedUnknownDuplicateAndWrongTypes()throws Exception {
        String json="{\"x\":1,\"y\":2,\"z\":3}";
        assertEquals(new Point(1,2,3),QualificationBinding.decode(json,Point.class));
        for(String bad:new String[]{"{\"x\":1,\"x\":2,\"y\":2,\"z\":3}","{\"x\":1,\"y\":2,\"z\":3,\"extra\":0}","{\"x\":\"1\",\"y\":2,\"z\":3}","{\"x\":1.5,\"y\":2,\"z\":3}","{\"x\":null,\"y\":2,\"z\":3}"})assertThrows(IOException.class,()->QualificationBinding.decode(bad,Point.class));
    }
    @Test void semanticIntentIsImmutableAcrossRestart()throws Exception {
        UUID id=UUID.randomUUID();Path dir=world.resolve("worldprep-v2/physical/test/qualification-"+id);
        var protocol=new QualificationBinding(world,dir,Input.class);
        var input=QualificationBinding.JSON.toJsonTree(new Input(3,id,world.relativize(protocol.first()).toString(),null,world.resolveSibling(world.getFileName()+"-a").toString(),world.resolveSibling(world.getFileName()+"-b").toString(),new Point(1,2,3))).getAsJsonObject();input.remove("backupSha256");String text=QualificationBinding.canonical(input);
        String hash=protocol.freeze(text);assertEquals(hash,new QualificationBinding(world,dir,Input.class).freeze(text));
        assertThrows(IOException.class,()->protocol.freeze(text.replace("\"x\":1","\"x\":4")));
        assertThrows(IOException.class,protocol::verify);
    }
    @Test void onlyFixedRequestAndBatchDirectoryAreExcluded()throws Exception {
        UUID id=UUID.randomUUID();Path dir=world.resolve("worldprep-v2/physical/test/qualification-"+id);
        var protocol=new QualificationBinding(world,dir,Input.class);
        assertEquals(java.util.Set.of(world.relativize(dir),Path.of("worldprep-qualification.json")),protocol.exclusions());
        assertFalse(protocol.exclusions().contains(Path.of("level.dat")));
        assertThrows(IOException.class,()->new QualificationBinding(world,world.resolve("outside"),Input.class));
    }
}
