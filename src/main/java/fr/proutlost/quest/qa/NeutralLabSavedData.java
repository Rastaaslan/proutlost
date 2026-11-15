package fr.proutlost.quest.qa;

import fr.proutlost.quest.choice.CollectiveVote;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.perception.PerceptionSession;
import fr.proutlost.quest.runtime.SavedDataText;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** TEST_ONLY data. Never reads Campaign.load, PerceptionContent or QuestSavedData. */
public final class NeutralLabSavedData extends SavedData {
    public static final String FILE_ID = "proutlost_quest_neutral_qa_v1";
    public static final Factory<NeutralLabSavedData> FACTORY =
        new Factory<>(NeutralLabSavedData::new, NeutralLabSavedData::load);
    static final QuestCore.Calibration CALIBRATION = new QuestCore.Calibration(2, 1, 0);
    QuestCore core = new QuestCore(definitions(), CALIBRATION);
    UUID session = UUID.randomUUID();
    Set<UUID> participants = new HashSet<>();
    PerceptionSession scene;
    CollectiveVote vote;
    String outcome = "";

    static List<QuestDefinition> definitions() { return NeutralLabCampaign.load().definitions(); }
    void requireAvailable(String id, UUID player) {
        if (core.state(id, player) == QuestCore.State.LOCKED) throw new IllegalStateException("Étape inaccessible : " + id);
    }
    void emit(String type, UUID actor, long now) {
        String id = "QA_ONLY:" + session + ":" + type;
        if (core.factById(id).isEmpty()) core.accept(new WorldFact(id, type, "proutlost:neutral_qa",
            Set.of(), "", "", "QA_ONLY", now, Map.of("actor", actor.toString()), true, WorldFact.Category.NARRATIVE));
        core.evaluate(now); setDirty();
    }
    void reset() {
        core = new QuestCore(definitions(), CALIBRATION); session = UUID.randomUUID();
        participants.clear(); scene = null; vote = null; outcome = ""; setDirty();
    }
    public static NeutralLabSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        if (root.getInt("schema") != 1) throw new IllegalStateException("Unsupported neutral QA schema");
        var data = new NeutralLabSavedData();
        if(root.contains("fixtureSha256")&&!root.getString("fixtureSha256").equals(NeutralLabCampaign.load().sha256()))
            throw new IllegalStateException("Saved neutral fixture differs; preserve it and use a separate QA world");
        data.session = UUID.fromString(root.getString("session"));
        data.core = QuestCore.restore(definitions(), CALIBRATION, SavedDataText.get(root,"core"));
        for (String id : root.getString("participants").split("\n"))
            if (!id.isBlank()) data.participants.add(UUID.fromString(id));
        if (!data.participants.isEmpty() && data.participants.size() != 2)
            throw new IllegalStateException("Neutral QA requires exactly two saved participants");
        if (root.contains("scene")) data.scene = PerceptionSession.restore(SavedDataText.get(root,"scene"));
        if (root.contains("vote")) data.vote = CollectiveVote.restore(SavedDataText.get(root,"vote"));
        data.outcome = root.getString("outcome"); return data;
    }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putString("fixtureSha256",NeutralLabCampaign.load().sha256());
        root.putInt("schema", 1); root.putString("session", session.toString());
        SavedDataText.put(root,"core", core.save()); root.putString("outcome", outcome);
        root.putString("participants", String.join("\n", participants.stream().sorted().map(UUID::toString).toList()));
        if (scene != null) SavedDataText.put(root,"scene", scene.save()); else root.remove("scene");
        if (vote != null) SavedDataText.put(root,"vote", vote.save()); else root.remove("vote");
        return root;
    }
}
