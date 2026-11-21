package fr.proutlost.worldprep;
import fr.proutlost.worldprep.world.ExistingChunkAccess;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExistingChunkAccessTest {
 @Test void missingAndIncompleteChunksCleanlyRefuseWithoutGeneration() {
  AtomicInteger calls=new AtomicInteger(); var missing=new ExistingChunkAccess<String>((d,x,z)->{calls.incrementAndGet();return Optional.empty();},Set.of("FULL"));
  assertEquals(ExistingChunkAccess.Refusal.MISSING_CHUNK,missing.require("minecraft:overworld",4,-2).refusal()); assertEquals(1,calls.get());
  var incomplete=new ExistingChunkAccess<String>((d,x,z)->Optional.of(new ExistingChunkAccess.Chunk<>("chunk","BIOMES")),Set.of("FULL"));
  assertEquals(ExistingChunkAccess.Refusal.INCOMPLETE_STATUS,incomplete.require("minecraft:overworld",0,0).refusal());
 }
}
