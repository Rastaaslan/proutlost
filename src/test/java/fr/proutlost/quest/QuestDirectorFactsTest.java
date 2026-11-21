package fr.proutlost.quest;

import fr.proutlost.director.api.model.*;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.runtime.QuestDirectorFacts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestDirectorFactsTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID REGION = UUID.fromString("00000000-0000-0000-0000-000000000012");

    private static DirectorFact actual(boolean confirmed) {
        return new DirectorFact(
            new FactId(UUID.fromString("00000000-0000-0000-0000-000000000013")),
            new FactType("proutlost_director:qa_fact"),
            List.of(new ScopeRef(ScopeKind.PLAYER, PLAYER), new ScopeRef(ScopeKind.REGION, REGION)),
            Optional.empty(), confirmed ? EvidenceStatus.CONFIRMED : EvidenceStatus.POSSIBLE,
            Map.of("actor",new JsonValue.Text(PLAYER.toString())), true, Optional.empty());
    }

    @Test void confirmedDirectorFactPreservesPrivateScopeAndDoesNotForceCollectiveProgress() {
        var worldFact = QuestDirectorFacts.translate(actual(true),123L);
        assertEquals("00000000-0000-0000-0000-000000000013",worldFact.factId());
        assertEquals("proutlost_director:j04",worldFact.sourceSystem());
        assertEquals("proutlost_director:qa_fact",worldFact.factType());
        assertEquals(WorldFact.Category.HYBRID,worldFact.narrativeVisibility());
        assertEquals(Set.of(PLAYER),worldFact.playerScope());
        assertEquals(REGION.toString(),worldFact.regionId());
        assertEquals(PLAYER.toString(),worldFact.payload().get("actor"));
        var core=new QuestCore(Campaign.load(),new QuestCore.Calibration(2,.6,10));
        core.accept(worldFact);
        core.accept(worldFact);
        assertTrue(core.factById(worldFact.factId()).isEmpty());
        core.evaluate(123L);
        assertEquals(QuestCore.State.AVAILABLE,core.state("P0"));
    }

    @Test void unconfirmedOrNonDurableDirectorFactsCannotBeTranslated() {
        assertThrows(IllegalArgumentException.class,()->QuestDirectorFacts.translate(actual(false),1));
        var fact=actual(true);
        var volatileFact=new DirectorFact(fact.factId(),fact.factType(),fact.subjectRefs(),fact.eventId(),
            fact.evidenceStatus(),fact.payload(),false,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->QuestDirectorFacts.translate(volatileFact,1));
    }
    @Test void explicitCanonicalDirectorQuestFactRequiresDeclaredObservation() {
        var type=Campaign.load().stream().filter(q->q.questId().equals("P0"))
            .findFirst().orElseThrow().observations().iterator().next();
        assertEquals(type,QuestDirectorFacts.canonicalType(
            "proutlost_director:quest_fact/"+type.toLowerCase(Locale.ROOT)));
        assertEquals("proutlost_director:catalogue_event_completed",
            QuestDirectorFacts.canonicalType("proutlost_director:catalogue_event_completed"));
        assertThrows(IllegalArgumentException.class,()->QuestDirectorFacts.canonicalType(
            "proutlost_director:quest_fact/nonexistent_qa_proof"));
    }
    @Test void explicitJ06CollectionUsesExistingPrivateObservationWithoutProgression(){
        var witness=new PlayerId(PLAYER);var other=UUID.fromString("00000000-0000-0000-0000-000000000099");
        var header=new PerceptionHeader(1,new WorldId(new UUID(50,1)),new Epoch(1),new Epoch(2),new DimensionId("minecraft:overworld"),
            new EventInstanceId(new UUID(50,2)),new EffectId(new UUID(50,3)),new Sequence(1),new DurationTicks(80));
        var source=new PerceptionUpsert(header,PerceptionKind.SOUND,new PerceptionTransform(0,64,0,0,0,1),
            new ResourceId("fixture:neutral_sound"),new Fingerprint("a".repeat(64)),new SoundParameters(.4,1,12));
        var request=new RecordObservation(header,new RecordObservationPayload(Optional.of(header.effectId()),Optional.empty(),Optional.of("neutral annotation")));
        var fact=fr.proutlost.director.adapter.neoforge.network.DirectorRecordedPerception.fact(witness,request,source,123);
        var observation=QuestDirectorFacts.recordedObservation(fact).orElseThrow();
        assertEquals(fr.proutlost.director.adapter.neoforge.network.DirectorRecordedPerception.text(fact),observation.text());
        assertFalse(observation.text().contains("fixture:neutral_sound"));
        var calibration=new QuestCore.Calibration(2,.6,10);var core=new QuestCore(Campaign.load(),calibration);
        var before=core.state("P0");core.observe(observation);core.observe(observation);core.evaluate(124);
        assertEquals(before,core.state("P0"));assertEquals(1,core.recordedPerceptions(PLAYER).size());
        assertTrue(core.recordedPerceptions(other).isEmpty());assertTrue(core.evidence(other,observation.id()).isEmpty());
        assertThrows(SecurityException.class,()->core.share(other,observation.id(),Set.of(PLAYER)));
        core.share(PLAYER,observation.id(),Set.of(other));
        assertEquals(QuestCore.Knowledge.SHARED_EVIDENCE,core.recordedPerceptions(other).getFirst().level());
        var restored=QuestCore.restore(Campaign.load(),calibration,core.save());
        assertEquals(observation,restored.evidence(PLAYER,observation.id()).orElseThrow());
        assertEquals(observation,restored.evidence(other,observation.id()).orElseThrow());
        assertEquals(1,restored.recordedPerceptions(PLAYER).size());assertEquals(1,restored.recordedPerceptions(other).size());
        var publicFact=new DirectorFact(fact.factId(),fact.factType(),List.of(new ScopeRef(ScopeKind.WORLD,header.worldId().value())),fact.eventId(),fact.evidenceStatus(),fact.payload(),true,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->QuestDirectorFacts.recordedObservation(publicFact));
    }

}
