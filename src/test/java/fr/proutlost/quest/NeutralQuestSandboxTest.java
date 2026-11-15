package fr.proutlost.quest;

import com.google.gson.JsonParser;
import fr.proutlost.quest.choice.CollectiveVote;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.perception.PerceptionSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * QA_ONLY neutral, headless fixture test. Does not load narrative data, launch Minecraft,
 * alter SavedData, or claim real client render / World Director J04 production.
 */
class NeutralQuestSandboxTest {
    private static final UUID A = new UUID(4, 1), B = new UUID(4, 2);

    private static QuestDefinition step(String id, Set<String> prerequisites, String proof) {
        return new QuestDefinition(id, id, id, prerequisites, Set.of(), "SERVER",
            Set.of(proof), List.of(Set.of(proof)), "QA neutral context", "QA test action",
            "QA passed", Set.of(), "QA only", false);
    }

    private static List<QuestDefinition> neutralDefinitions() {
        return List.of(
            step("QA-00", Set.of(), "QA_STARTED"),
            step("QA-01A", Set.of("QA-00"), "SOUND_RECORDED"),
            step("QA-01B", Set.of("QA-00"), "SYMBOLS_RECORDED"),
            step("QA-02", Set.of("QA-01A", "QA-01B"), "QA_JOINED"),
            step("QA-03", Set.of("QA-02"), "QA_RENDERED"),
            step("QA-04", Set.of("QA-03"), "QA_VOTED"),
            step("QA-05", Set.of("QA-04"), "QA_COMPLETE"));
    }

    private static QuestCore newCore() {
        return new QuestCore(neutralDefinitions(), new QuestCore.Calibration(2, 0.5, 0));
    }

    private static void fact(QuestCore core, String type, int seq) {
        core.accept(new WorldFact("QA_ONLY:" + seq, type, "qa_neutral_unit_test", Set.of(),
            "", "", "qa_world", 100 + seq, Map.of(), true, WorldFact.Category.NARRATIVE));
        core.evaluate(100 + seq);
    }

    private static boolean visible(QuestCore core, UUID viewer, String id) {
        return core.evidence(viewer, id).isPresent();
    }

    private static String fixture(String name) throws java.io.IOException {
        try (var input = NeutralQuestSandboxTest.class.getResourceAsStream("/quest-neutral-campaign-qa/" + name)) {
            assertNotNull(input, "Missing versioned neutral fixture: " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static List<String> idsFromPackagedLoader() {
        return fr.proutlost.quest.qa.NeutralLabCampaign.load().definitions().stream()
            .map(QuestDefinition::questId).toList();
    }

    @Test
    void guardedExecutableFixturesAreStrictlySeparateAndWellFormed() throws Exception {
        var manifest = JsonParser.parseString(fixture("manifest.json")).getAsJsonObject();
        var steps = JsonParser.parseString(fixture("fixtures/steps.json")).getAsJsonArray();
        assertEquals("TEST_ONLY", manifest.get("mode").getAsString());
        assertEquals("EXECUTABLE_TEST_ONLY_PENDING_MULTIPLAYER", manifest.get("status").getAsString());
        assertEquals("0.2", manifest.get("version").getAsString());
        assertTrue(manifest.get("requiresTestOnlyGuard").getAsBoolean());
        assertTrue(manifest.get("requiresClientAandB").getAsBoolean());
        assertTrue(manifest.get("noNarrative").getAsBoolean());
        assertEquals("fr.proutlost.quest.qa.NeutralLabCampaign", manifest.get("loader").getAsString());
        assertEquals("fr.proutlost.quest.qa.NeutralLabRuntime", manifest.get("runtime").getAsString());
        assertEquals("/data/proutlost/quest_qa/steps.json", manifest.get("resource").getAsString());
        assertEquals(idsFromPackagedLoader(), manifest.getAsJsonArray("steps").asList().stream()
            .map(com.google.gson.JsonElement::getAsString).toList());
        assertFalse(manifest.get("liveWorldAccess").getAsBoolean());
        assertFalse(manifest.get("useProductionCampaignResources").getAsBoolean());
        assertTrue(manifest.get("requiresSeparateWorld").getAsBoolean());
        assertTrue(manifest.get("requiresSeparateSavedData").getAsBoolean());
        assertEquals(7, steps.size());
        Set<String> ids = new HashSet<>();
        steps.forEach(element -> assertTrue(ids.add(element.getAsJsonObject().get("id").getAsString())));
        assertEquals(neutralDefinitions().stream().map(QuestDefinition::questId).collect(java.util.stream.Collectors.toSet()), ids);
        // Executable only through the isolated neutralLab runtime; fixed production resources stay separate.
        assertFalse(fixture("README.txt").isBlank());
    }

    @Test
    void fictionalBranchRequiresTwoRealEnginePerceptionsSharingAndReplayedState() {
        QuestCore core = newCore();
        core.active(A, 100); core.active(B, 100);
        assertEquals(QuestCore.State.AVAILABLE, core.state("QA-00"));
        assertEquals(QuestCore.State.LOCKED, core.state("QA-02"));
        fact(core, "QA_STARTED", 0);
        assertEquals(QuestCore.State.RESOLVED, core.state("QA-00"));

        var scene = new PerceptionSession("QA_NEUTRAL_01",
            List.of("grave", "moyen", "aigu"), List.of("SIGN-A", "SIGN-B", "SIGN-C"));
        var initial = Map.of(A, new PerceptionSession.Position("qa_world", 0, 64, 0),
            B, new PerceptionSession.Position("qa_world", 8, 64, 0));
        scene.play(initial, 100);
        var a = scene.forPlayer(A).getLast();
        var b = scene.forPlayer(B).getLast();
        assertNotEquals(a.family(), b.family());
        assertThrows(SecurityException.class, () -> scene.record(B, a.id(), core));
        scene.record(A, a.id(), core); scene.record(B, b.id(), core);
        assertTrue(visible(core, A, a.id()));
        assertFalse(visible(core, A, b.id()));
        assertTrue(visible(core, B, b.id()));
        assertFalse(visible(core, B, a.id()));
        core.evaluate(101);
        assertEquals(QuestCore.State.AVAILABLE, core.state("QA-01A"));
        assertEquals(QuestCore.State.AVAILABLE, core.state("QA-01B"));
        assertThrows(SecurityException.class, () -> core.share(B, a.id(), Set.of(B)));
        core.share(A, a.id(), Set.of(B));
        assertTrue(visible(core, B, a.id()));
        assertFalse(visible(core, A, b.id()));
        core.share(B, b.id(), Set.of(A));
        core.evaluate(102);
        assertEquals(QuestCore.State.RESOLVED, core.state("QA-01A"));
        assertEquals(QuestCore.State.RESOLVED, core.state("QA-01B"));
        assertEquals(QuestCore.State.AVAILABLE, core.state("QA-02"));

        assertThrows(IllegalStateException.class, () -> scene.play(initial, 103));
        scene.play(Map.of(A, new PerceptionSession.Position("qa_world", 3, 64, 0),
            B, new PerceptionSession.Position("qa_world", 11, 64, 0)), 104);
        assertNotEquals(a.family(), scene.forPlayer(A).getLast().family());
        assertNotEquals(b.family(), scene.forPlayer(B).getLast().family());
        assertEquals(2, scene.trial());

        var restoredCore = QuestCore.restore(neutralDefinitions(), new QuestCore.Calibration(2, 0.5, 0), core.save());
        var restoredScene = PerceptionSession.restore(scene.save());
        assertEquals(2, restoredScene.trial());
        assertTrue(visible(restoredCore, B, a.id()));
        assertTrue(visible(restoredCore, A, b.id()));
        assertEquals(QuestCore.State.AVAILABLE, restoredCore.state("QA-02"));

        fact(restoredCore, "QA_JOINED", 1);
        assertEquals(QuestCore.State.RESOLVED, restoredCore.state("QA-02"));
        fact(restoredCore, "QA_RENDERED", 2);  // synthetic flag ONLY; not a graphical rendering claim
        assertEquals(QuestCore.State.RESOLVED, restoredCore.state("QA-03"));

        var vote = new CollectiveVote("QA-04",
            new CollectiveVote.Rules(0, 10, 1.0),
            new CollectiveVote.Dossier(List.of("OPTION_A", "OPTION_B"), "QA effects", "QA facts", "QA warning"), 0);
        vote.consult(A); vote.consult(B);
        vote.open(Set.of(A, B), 0);
        vote.confirm(A, "OPTION_A", 1);
        assertThrows(IllegalStateException.class, () -> vote.confirm(A, "OPTION_B", 1));
        vote.confirm(B, "OPTION_A", 2);
        var persisted = CollectiveVote.restore(vote.save());
        assertEquals(Set.of(A, B), persisted.confirmedVoters());
        assertEquals(CollectiveVote.Phase.DECIDED, persisted.close(10));
        assertEquals("OPTION_A", persisted.result());
        fact(restoredCore, "QA_VOTED", 3);
        assertEquals(QuestCore.State.RESOLVED, restoredCore.state("QA-04"));
        fact(restoredCore, "QA_COMPLETE", 4);
        assertEquals(QuestCore.State.RESOLVED, restoredCore.state("QA-05"));
        var restored = QuestCore.restore(neutralDefinitions(), new QuestCore.Calibration(2, 0.5, 0), restoredCore.save());
        assertEquals(QuestCore.State.RESOLVED, restored.state("QA-05"));
        assertEquals(7, restored.journal(A).size());
    }
}
