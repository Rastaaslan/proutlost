package fr.proutlost.quest.runtime;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.quest.DirectorCommittedFactEvent;
import fr.proutlost.quest.core.WorldFact;
import fr.proutlost.quest.core.Campaign;
import java.util.*;
import net.neoforged.neoforge.common.NeoForge;

/** Optional passive WDQ adapter. Does not own Director and never emits events back to it. */
public final class QuestDirectorFacts {
    private QuestDirectorFacts() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(QuestDirectorFacts::onConfirmed);
    }

    private static void onConfirmed(DirectorCommittedFactEvent event) {
        var server = event.server();
        if (!server.isSameThread()) throw new IllegalStateException("Quest Director facts need server actor");
        if (!QuestConfig.ENABLED.get()) return;
        var recorded=recordedObservation(event.fact());
        if(recorded.isPresent()){
            var data=QuestRuntime.data(server);data.core().observe(recorded.orElseThrow());data.setDirty();return;
        }
        var translated = translate(event.fact(), System.currentTimeMillis());
        var core = QuestRuntime.data(server).core();
        // A cold J04 replay retains FactId, not a wall-clock timestamp. Reuse the
        // original durable Quest fact if one was recorded on a previous process.
        var prior = core.factById(translated.factId());
        if (prior.isPresent()) {
            var old = prior.orElseThrow();
            if (!old.factType().equals(translated.factType()) || !old.sourceSystem().equals(translated.sourceSystem())
                || !old.playerScope().equals(translated.playerScope()) || !old.groupScope().equals(translated.groupScope())
                || !old.regionId().equals(translated.regionId()) || !old.locationId().equals(translated.locationId())
                || !old.payload().equals(translated.payload()) || old.narrativeVisibility()!=translated.narrativeVisibility())
                throw new IllegalArgumentException("Director fact replay conflict");
            return;
        }
        QuestRuntime.publish(server, translated);
    }

    /** A collected J06 fact remains an observation of its sole witness, never a quest proof. */
    public static Optional<fr.proutlost.quest.core.QuestCore.Observation> recordedObservation(DirectorFact fact){
        if(!fact.factType().value().equals("proutlost_director:recorded_perception"))return Optional.empty();
        if(!fact.durable()||fact.evidenceStatus()!=EvidenceStatus.CONFIRMED||fact.subjectRefs().size()!=1
                ||fact.subjectRefs().getFirst().kind()!=ScopeKind.PLAYER)throw new IllegalArgumentException("private collected fact required");
        var witness=fact.subjectRefs().getFirst().id();
        String claimed=((JsonValue.Text)fact.payload().get("witness")).value();
        long time=Long.parseLong(((JsonValue.Text)fact.payload().get("recorded_at")).value());
        var source=new fr.proutlost.director.api.codec.ModelCodec<>(PerceptionUpsert.class).decode(Base64.getDecoder().decode(((JsonValue.Text)fact.payload().get("source")).value()));
        String kind=((JsonValue.Text)fact.payload().get("kind")).value(),asset=((JsonValue.Text)fact.payload().get("asset")).value();
        String note=((JsonValue.Text)fact.payload().get("player_text")).value();
        if(!claimed.equals(witness.toString())||time<0||note.length()>1000||!kind.equals(source.kind().name())||!asset.equals(source.assetId().value())
                ||!fact.eventId().equals(Optional.of(source.header().instanceId())))throw new IllegalArgumentException("collected source conflict");
        String label=switch(source.kind()){
            case SOUND->"Son perçu";case PARTICLES->"Particules aperçues";case APPARITION->"Silhouette aperçue";
            case LOCAL_WEATHER->"Ambiance perçue";case GHOST->"Forme aperçue";case SYMBOL->"Symbole aperçu";
        };
        return Optional.of(new fr.proutlost.quest.core.QuestCore.Observation(fact.factId().value().toString(),witness,"DIRECTOR_PERCEPTION",
            label+(note.isBlank()?"":" — "+note),"proutlost_director:perception/"+source.header().worldId().value()+"/"+source.header().instanceId().value(),time));
    }
    /** Director fact types are legal resource IDs (lower-case); quest proofs are canonical upper-case IDs.
     * Only an explicit quest_fact/ namespace may become a quest proof, and only for a CDC-defined type.
     * A generic AMBIENT/Director completion must never silently unlock an unrelated quest. */
    public static String canonicalType(String sourceType) {
        String prefix="proutlost_director:quest_fact/";
        if (!sourceType.startsWith(prefix)) return sourceType;
        String candidate=sourceType.substring(prefix.length()).toUpperCase(Locale.ROOT);
        boolean known=Campaign.load().stream().anyMatch(q->q.observations().contains(candidate));
        if (!known) throw new IllegalArgumentException("Director fact not declared in QUESTSPEC");
        return candidate;
    }

    /** The Director fact is immutable. Player scopes never widen to the public. */
    public static WorldFact translate(DirectorFact fact, long receivedAt) {
        Objects.requireNonNull(fact);
        if (!fact.durable() || fact.evidenceStatus()!=EvidenceStatus.CONFIRMED)
            throw new IllegalArgumentException("Unconfirmed Director fact");
        var players = new HashSet<UUID>();
        String region = "", location = "";
        for (var scope:fact.subjectRefs()) {
            switch(scope.kind()) {
                case PLAYER -> players.add(scope.id());
                case REGION -> { if (!region.isEmpty() && !region.equals(scope.id().toString())) throw new IllegalArgumentException("Ambiguous region"); region = scope.id().toString(); }
                case LOCATION -> { if (!location.isEmpty() && !location.equals(scope.id().toString())) throw new IllegalArgumentException("Ambiguous location"); location = scope.id().toString(); }
                case WORLD, EVENT -> { }
            }
        }
        var payload = new HashMap<String,String>();
        for (var entry:fact.payload().entrySet())
            if (entry.getValue() instanceof JsonValue.Text value) payload.put(entry.getKey(),value.value());
        return new WorldFact(fact.factId().value().toString(), canonicalType(fact.factType().value()), "proutlost_director:j04",
            players, "",region,location,receivedAt,payload,true,WorldFact.Category.HYBRID);
    }
}
