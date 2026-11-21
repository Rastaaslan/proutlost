package fr.proutlost.worldprep;

import fr.proutlost.worldprep.build.*;
import fr.proutlost.worldprep.pack.*;
import fr.proutlost.worldprep.pipeline.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldPrepV2ModelsTest {
 @TempDir java.nio.file.Path temp;
 @Test void structuresPrecedeVegetationAndInvalidateIt() {
   assertTrue(PassGraph.order().indexOf(PassId.STRUCTURES) < PassGraph.order().indexOf(PassId.FLORA));
   assertTrue(PassGraph.invalidatedBy(PassId.STRUCTURES).containsAll(Set.of(PassId.FLORA, PassId.TREES, PassId.VALIDATION)));
 }
 @Test void performanceModDoesNotChangeSemanticIdentity() {
   var perf = new ModArtifact("perf", "1", "aa", ContentClassification.PERFORMANCE_ONLY, Set.of());
   var semantic = new ModArtifact("environment", "1", "bb", ContentClassification.ENVIRONMENT_SEMANTIC, Set.of(ContentCapability.BLOCKS));
   var a = pack(List.of(perf, semantic)); var b = pack(List.of(semantic));
   assertNotEquals(a.fingerprint(), b.fingerprint());
   assertEquals(EnvironmentalSemanticSnapshot.from(a).fingerprint(), EnvironmentalSemanticSnapshot.from(b).fingerprint());
 }
 @Test void unclassifiedContentRefusesProduction() {
   var unknown = new ModArtifact("unknown", "1", "cc", ContentClassification.UNCLASSIFIED, Set.of());
   assertThrows(IllegalStateException.class, () -> EnvironmentalSemanticSnapshot.from(pack(List.of(unknown))));
 }
 @Test void workspaceRejectsTraversalOverlapAndExistingCandidate() throws Exception {
   var sources=Files.createDirectory(temp.resolve("sources")); var builds=Files.createDirectory(temp.resolve("builds"));
   Files.createDirectory(sources.resolve("river")); var manager=new BuildWorkspaceManager(sources, builds);
   var id=new BuildIdentity("candidate", "source-sha", "pack-sha");
   assertEquals(BuildState.SOURCE_VERIFIED, manager.validate("river", "candidate", id).state());
   assertThrows(IllegalArgumentException.class, () -> manager.validate("../river", "candidate", id));
   Files.createDirectory(builds.resolve("existing"));
   assertThrows(java.io.IOException.class, () -> manager.validate("river", "existing", id));
   assertThrows(IllegalArgumentException.class, () -> new BuildWorkspaceManager(sources, sources.resolve("river")));
 }
 @Test void candidateCopyIsVerifiedAndSourceRemainsUnchanged() throws Exception {
   var sources=Files.createDirectory(temp.resolve("copy-sources")); var builds=Files.createDirectory(temp.resolve("copy-builds"));
   var source=Files.createDirectory(sources.resolve("river")); Files.writeString(source.resolve("level.dat"),"immutable");
   var fingerprint=SourceWorldFingerprint.capture(source); var identity=new BuildIdentity("candidate",fingerprint.value(),"pack");
   var manager=new BuildWorkspaceManager(sources,builds); var build=manager.createCandidate("river","candidate",identity);
   assertEquals(BuildState.PLANNING,build.state()); assertEquals("immutable",Files.readString(source.resolve("level.dat")));
   assertEquals("immutable",Files.readString(build.candidate().resolve("level.dat"))); assertEquals(BuildState.PLANNING,manager.readState(build.candidate()));
 }
 private static FullPackSnapshot pack(List<ModArtifact> mods) { return new FullPackSnapshot("1.21.1","21.1.248",mods,List.of(new RegistryFingerprint("minecraft:block","dd")),List.of("data"),List.of("config"),List.of()); }
}
