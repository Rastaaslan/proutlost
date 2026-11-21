package fr.proutlost.quest.runtime;

import fr.proutlost.quest.core.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Overworld-owned, independent of dimension and WorldPrep state. */
public final class QuestSavedData extends SavedData {
    public static final String FILE_ID="proutlost_quest_v1";
    public static final Factory<QuestSavedData> FACTORY=new Factory<>(QuestSavedData::new, QuestSavedData::load);
    private CampaignState state;
    private final java.util.Set<java.util.UUID> arrivals=new java.util.HashSet<>();
    public java.util.Set<java.util.UUID> arrivals(){return arrivals;}
    private final java.util.Set<String> routineLocations=new java.util.HashSet<>();
    public java.util.Set<String> routineLocations(){return routineLocations;}
    private fr.proutlost.quest.content.Investigation investigation=new fr.proutlost.quest.content.Investigation();
    public fr.proutlost.quest.content.Investigation investigation(){return investigation;}
    private fr.proutlost.quest.content.CampaignActions actions=new fr.proutlost.quest.content.CampaignActions();
    public fr.proutlost.quest.content.CampaignActions actions(){return actions;}
    private PreparedSites sites=new PreparedSites();
    public QuestSavedData() { this(calibration()); }
    public QuestSavedData(QuestCore.Calibration calibration) { this(calibration,Campaign.load()); }
    private QuestSavedData(QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) { state=new CampaignState(new QuestCore(definitions,calibration)); }
    /** Closed TEST_ONLY profile. Canonical factory/file ID and definitions are unchanged. */
    public static QuestSavedData publicGroupQualification(QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) {
        requirePublicGroupQualification(definitions);return new QuestSavedData(calibration,definitions);
    }
    private static void requirePublicGroupQualification(java.util.List<QuestDefinition> definitions) {
        if(!Boolean.getBoolean("proutlost.quest.publicGroupQa")
            ||!definitions.equals(fr.proutlost.quest.qa.PublicGroupQaFixture.quests()))
            throw new IllegalStateException("closed public GROUP qualification profile required");
        Campaign.validate(definitions);
    }
    public static QuestSavedData readPublicGroupQualification(CompoundTag root,QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) {
        requirePublicGroupQualification(definitions);return read(root,calibration,definitions);
    }
    private static void requireRealGroupClientsQualification(java.util.List<QuestDefinition> definitions) {
        if(!Boolean.getBoolean("proutlost.quest.realGroupClientsQa")
            ||!definitions.equals(fr.proutlost.quest.qa.RealGroupClientsQaFixture.quests()))
            throw new IllegalStateException("closed real-client GROUP qualification profile required");
        Campaign.validate(definitions);
    }
    public static QuestSavedData realGroupClientsQualification(QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) {
        requireRealGroupClientsQualification(definitions);return new QuestSavedData(calibration,definitions);
    }
    public static QuestSavedData readRealGroupClientsQualification(CompoundTag root,QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) {
        requireRealGroupClientsQualification(definitions);return read(root,calibration,definitions);
    }
    private static QuestCore.Calibration calibration() {
        return new QuestCore.Calibration(QuestConfig.MIN_CONTRIBUTORS.get(),QuestConfig.ACTIVE_RATIO.get(),QuestConfig.CIRCULATION_SECONDS.get()*1000L);
    }
    public QuestCore core() { return state.core(); }
    public CampaignState state() { return state; }
    public PreparedSites sites() { return sites; }
    public static QuestSavedData load(CompoundTag root, HolderLookup.Provider registries) { return read(root,calibration()); }
    public static QuestSavedData read(CompoundTag root,QuestCore.Calibration calibration) {
        return read(root,calibration,Campaign.load());
    }
    private static QuestSavedData read(CompoundTag root,QuestCore.Calibration calibration,java.util.List<QuestDefinition> definitions) {
        if (root.getInt("schema")!=1) throw new IllegalStateException("Unsupported Quest SavedData schema");
        var data=new QuestSavedData(calibration,definitions);
        if(root.contains("arrivals"))for(var id:SavedDataText.get(root,"arrivals").split("\n"))if(!id.isBlank())data.arrivals.add(java.util.UUID.fromString(id));
        if(root.contains("routineLocations"))data.routineLocations.addAll(java.util.Arrays.asList(SavedDataText.get(root,"routineLocations").split("\n"))); data.state=CampaignState.restore(definitions,calibration,SavedDataText.get(root,"campaign"));
        if(root.contains("actions"))data.actions=fr.proutlost.quest.content.CampaignActions.restore(SavedDataText.get(root,"actions"));
        if(root.contains("investigation"))data.investigation=fr.proutlost.quest.content.Investigation.restore(SavedDataText.get(root,"investigation"));
        if (root.contains("sites")) data.sites=PreparedSites.restore(SavedDataText.get(root,"sites")); return data;
    }
    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("schema",1); SavedDataText.put(root,"arrivals",String.join("\n",arrivals.stream().map(java.util.UUID::toString).toList())); SavedDataText.put(root,"routineLocations",String.join("\n",routineLocations)); SavedDataText.put(root,"campaign",state.save()); SavedDataText.put(root,"sites",sites.save()); SavedDataText.put(root,"investigation",investigation.save()); SavedDataText.put(root,"actions",actions.save()); return root;
    }
}
