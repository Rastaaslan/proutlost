package fr.proutlost.quest;

import fr.proutlost.quest.core.*;
import fr.proutlost.quest.runtime.*;
import fr.proutlost.quest.perception.PerceptionSession;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.proutlost.quest.QuestCoreTest.*;

/** Real pinned NBT/SavedData serialization on disk; not a running Minecraft-server restart. */
class QuestSavedDataTest {
    @TempDir Path directory;
    @Test void compressedNbtRoundTripRetainsPrivateProofSceneAndPreparedSites() throws Exception {
        var cal=new QuestCore.Calibration(2,.6,100);var data=new QuestSavedData(cal);
        data.core().observe(new QuestCore.Observation("private:id",A,"WRECK_MOTIF","square petal","wreck",1));
        data.sites().bind("wreck",new PreparedSites.Site(PreparedSites.Kind.WRECK,"minecraft:overworld",1,65,2));
        data.sites().captureBaseline("wreck","minecraft:stone");
        var scene=data.state().scene("scene",List.of("grave","moyen","aigu"),List.of("ICI","VOIR","LIER"));
        scene.play(Map.of(A,new PerceptionSession.Position("world",0,0,0),B,new PerceptionSession.Position("world",5,0,0)),2);
        scene.replaceWitness(B,C,new PerceptionSession.Position("world",5,0,0),Set.of(A,C),3,ignored -> false);
        var path=directory.resolve("proutlost_quest_v1.dat");NbtIo.writeCompressed(data.save(new CompoundTag(),null),path);
        var loaded=QuestSavedData.read(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()),cal);
        assertEquals(data.core().evidence(A,"private:id"),loaded.core().evidence(A,"private:id"));assertTrue(loaded.core().evidence(B,"private:id").isEmpty());
        assertEquals(data.sites().sites(),loaded.sites().sites());assertTrue(loaded.sites().changedPoint("wreck","minecraft:oak_planks"));
        var restored=loaded.state().scene("scene",List.of("grave","moyen","aigu"),List.of("ICI","VOIR","LIER"));
        restored.play(Map.of(A,new PerceptionSession.Position("world",5,0,0),C,new PerceptionSession.Position("world",0,0,0)),4);assertEquals(2,restored.trial());
    }

    @Test void compressedNbtRetainsCampaignBeyondUtfLimitAndReadsLegacyStrings() throws Exception {
        var cal=new QuestCore.Calibration(2,.6,100);var data=new QuestSavedData(cal);
        data.core().observe(new QuestCore.Observation("legacy:id",A,"WRECK_MOTIF","legacy QA proof","wreck",1));
        // A genuine old-schema StringTag file must still restore after the change.
        var legacy=new CompoundTag();legacy.putInt("schema",1);
        legacy.putString("campaign",data.state().save());legacy.putString("sites",data.sites().save());
        legacy.putString("investigation",data.investigation().save());legacy.putString("actions",data.actions().save());
        var legacyPath=directory.resolve("legacy-small.dat");NbtIo.writeCompressed(legacy,legacyPath);
        var fromLegacy=QuestSavedData.read(NbtIo.readCompressed(legacyPath,NbtAccounter.unlimitedHeap()),cal);
        assertEquals(data.core().evidence(A,"legacy:id"),fromLegacy.core().evidence(A,"legacy:id"));

        String text="é".repeat(40000);
        data.core().observe(new QuestCore.Observation("large:id",A,"WRECK_MOTIF",text,"wreck",2));
        assertTrue(data.state().save().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535);
        var path=directory.resolve("large-campaign.dat");
        NbtIo.writeCompressed(data.save(new CompoundTag(),null),path);
        var loaded=QuestSavedData.read(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()),cal);
        assertEquals(text,loaded.core().evidence(A,"large:id").orElseThrow().text());
        assertTrue(loaded.core().evidence(B,"large:id").isEmpty());
        assertEquals(data.core().evidence(A,"legacy:id"),loaded.core().evidence(A,"legacy:id"));
    }
    @Test void compressedAmbientNbtRetainsLargeMemoryAndSuppressionAndLegacyStrings() throws Exception {
        var memory=new fr.proutlost.ambient.CampRoutineMemory();var locations=new ArrayList<String>();
        for(int i=0;i<2500;i++){
            String location="minecraft:overworld:"+((((long)i*16)<<38)|64L);locations.add(location);
            memory.sample(location,Map.of(A,1),1794260000000L+i*1000L,30,60000,300000);
        }
        String memoryJson=memory.save(),suppressed=String.join("\n",locations);
        assertTrue(memoryJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535);
        assertTrue(suppressed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65535);
        // Seed the actual SavedData loader in memory; this oversized StringTag is never written to disk.
        var seed=new CompoundTag();seed.putInt("schema",1);seed.putString("memory",memoryJson);seed.putString("suppressed",suppressed);
        var data=fr.proutlost.ambient.AmbientRuntime.Data.FACTORY.deserializer().apply(seed,null);
        var saved=data.save(new CompoundTag(),null);
        assertTrue(saved.contains("memory",Tag.TAG_BYTE_ARRAY));assertTrue(saved.contains("suppressed",Tag.TAG_BYTE_ARRAY));
        var path=directory.resolve("large-ambient.dat");NbtIo.writeCompressed(saved,path);
        var restored=fr.proutlost.ambient.AmbientRuntime.Data.FACTORY.deserializer().apply(NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()),null);
        var actual=restored.save(new CompoundTag(),null);
        assertEquals(com.google.gson.JsonParser.parseString(memoryJson),com.google.gson.JsonParser.parseString(SavedDataText.get(actual,"memory")));
        assertEquals(new HashSet<>(locations),new HashSet<>(Arrays.asList(SavedDataText.get(actual,"suppressed").split("\n"))));

        var small=new fr.proutlost.ambient.CampRoutineMemory();small.sample("legacy-fire",Map.of(A,0),1,30,60000,300000);
        var legacy=new CompoundTag();legacy.putInt("schema",1);legacy.putString("memory",small.save());legacy.putString("suppressed","legacy-fire");
        var legacyPath=directory.resolve("legacy-ambient.dat");NbtIo.writeCompressed(legacy,legacyPath);
        var old=fr.proutlost.ambient.AmbientRuntime.Data.FACTORY.deserializer().apply(NbtIo.readCompressed(legacyPath,NbtAccounter.unlimitedHeap()),null);
        var migrated=old.save(new CompoundTag(),null);
        assertEquals(com.google.gson.JsonParser.parseString(small.save()),com.google.gson.JsonParser.parseString(SavedDataText.get(migrated,"memory")));
        assertEquals("legacy-fire",SavedDataText.get(migrated,"suppressed"));
    }

}
