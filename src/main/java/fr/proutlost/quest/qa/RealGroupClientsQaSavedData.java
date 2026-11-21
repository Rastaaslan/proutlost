package fr.proutlost.quest.qa;

import fr.proutlost.quest.runtime.QuestSavedData;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/** A new actual SavedData owns the real enrolled UUIDs and the normal Quest
 * persistence codec. No roster or team is reconstructed from a receipt. */
public final class RealGroupClientsQaSavedData extends SavedData {
 public static final String FILE_ID="proutlost_quest_real_group_clients_qa_v1";
 public static final Factory<RealGroupClientsQaSavedData> FACTORY=new Factory<>(RealGroupClientsQaSavedData::new,RealGroupClientsQaSavedData::load);
 private final QuestSavedData quest;
 private final Set<UUID> participants=new HashSet<>();
 public RealGroupClientsQaSavedData(){quest=QuestSavedData.realGroupClientsQualification(RealGroupClientsQaFixture.CALIBRATION,RealGroupClientsQaFixture.quests());}
 private RealGroupClientsQaSavedData(QuestSavedData quest){this.quest=quest;}
 public QuestSavedData quest(){return quest;}
 public Set<UUID> participants(){return Set.copyOf(participants);}
 public void enroll(MinecraftServer server,List<ServerPlayer> players){
  if(!server.isSameThread()||!participants.isEmpty()||players.size()!=2||players.getFirst()==players.getLast())throw new IllegalStateException("new pair of actual players required");
  for(var p:players){if(p.server!=server||server.getPlayerList().getPlayer(p.getUUID())!=p)throw new IllegalStateException("actual connected player required");participants.add(p.getUUID());}
  if(participants.size()!=2)throw new IllegalStateException("distinct authenticated UUIDs required");setDirty();
 }
 public static RealGroupClientsQaSavedData load(CompoundTag root,HolderLookup.Provider registries){
  if(root.getInt("schema")!=1||!root.getString("fixture_sha256").equals(RealGroupClientsQaFixture.catalog(fr.proutlost.director.bootstrap.DirectorLabObservationProfile.load()).hash().value()))throw new IllegalStateException("real-client GROUP SavedData fixture mismatch");
  var data=new RealGroupClientsQaSavedData(QuestSavedData.readRealGroupClientsQualification(root.getCompound("quest"),RealGroupClientsQaFixture.CALIBRATION,RealGroupClientsQaFixture.quests()));
  for(var id:root.getString("participants").split("\n"))if(!id.isBlank())data.participants.add(UUID.fromString(id));
  if(data.participants.size()!=2)throw new IllegalStateException("actual saved participant pair missing");return data;
 }
 @Override public CompoundTag save(CompoundTag root,HolderLookup.Provider registries){
  if(participants.size()!=2)throw new IllegalStateException("actual participant pair not enrolled");
  root.putInt("schema",1);root.putString("fixture_sha256",RealGroupClientsQaFixture.catalog(fr.proutlost.director.bootstrap.DirectorLabObservationProfile.load()).hash().value());
  root.putString("participants",String.join("\n",participants.stream().sorted().map(UUID::toString).toList()));root.put("quest",quest.save(new CompoundTag(),registries));return root;
 }
}
