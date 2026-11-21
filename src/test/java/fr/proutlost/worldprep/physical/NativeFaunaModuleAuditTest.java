package fr.proutlost.worldprep.physical;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeFaunaModuleAuditTest {
    @TempDir Path dir;
    private static final UUID BATCH = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OP = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SHA = "a".repeat(64);

    private CompoundTag animal(UUID uuid, String species, double x, double z, boolean owned) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", uuid); tag.putString("id", species); tag.putFloat("Health", 10);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(64)); pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        if (owned) {
            CompoundTag data = new CompoundTag();
            data.putString("proutlost_fauna_batch", BATCH.toString());
            data.putString("proutlost_fauna_operation", OP.toString());
            data.putString("proutlost_fauna_request", SHA);
            data.putString("proutlost_fauna_group", "forest-01");
            tag.put("NeoForgeData", data); tag.putBoolean("PersistenceRequired", true);
        }
        return tag;
    }

    private NativeFaunaModuleAudit.Expected expect(UUID uuid, String species, boolean owned) {
        return new NativeFaunaModuleAudit.Expected(uuid, species, owned ? OP : null,
                owned ? "forest-01" : "baseline", owned, owned);
    }

    private Path region(int cx, int cz, CompoundTag... entities) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3955); root.putIntArray("Position", new int[]{cx, cz});
        ListTag list = new ListTag(); for (CompoundTag entity : entities) list.add(entity);
        root.put("Entities", list);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) { NbtIo.write(root, out); }
        byte[] compressed = bytes.toByteArray();
        int sectors = (compressed.length + 5 + 4095) / 4096;
        ByteBuffer file = ByteBuffer.allocate((2 + sectors) * 4096);
        int slot = Math.floorMod(cx, 32) + 32 * Math.floorMod(cz, 32);
        file.putInt(slot * 4, 2 << 8 | sectors);
        file.position(8192); file.putInt(compressed.length + 1); file.put((byte)2); file.put(compressed);
        Path path = dir.resolve("r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
        Files.write(path, file.array()); return path;
    }

    private NativeFaunaModuleAudit.Result audit(NativeFaunaModuleAudit.Expected... expected) throws Exception {
        try (var audit = new NativeFaunaModuleAudit(dir, BATCH, SHA, List.of(expected))) {
            long until = System.nanoTime() + 10_000_000_000L;
            while (!audit.step()) {
                if (System.nanoTime() > until) fail("Asynchronous audit timeout");
                Thread.sleep(2);
            }
            return audit.result();
        }
    }

    @Test void provesOwnedAndBaselineAnimalsAndAcceptsNativeZeroLengthRegions() throws Exception {
        UUID owned = UUID.randomUUID(), baseline = UUID.randomUUID();
        region(0, 0, animal(owned, "reanimal:hedgehog", 0.5, 0.5, true),
                animal(baseline, "minecraft:cow", 8.5, 0.5, false));
        Files.write(dir.resolve("r.1.0.mca"), new byte[0]);
        var result = audit(expect(owned, "reanimal:hedgehog", true), expect(baseline, "minecraft:cow", false));
        assertEquals("FAUNA_NATIVE_ANVIL_UUIDS_VERIFIED", result.status());
        assertEquals(2, result.expectedCount()); assertEquals(2, result.totalEntities());
        assertEquals(2, result.scannedRegions()); assertEquals(1, result.scannedChunks());
        assertEquals(1, result.emptyRegions()); assertEquals(2, result.observations().size());
        assertEquals(8.5, result.observations().get(1).x());
        assertFalse(result.observations().get(1).owned());
        assertTrue(result.observations().get(0).region().endsWith("r.0.0.mca"));
    }

    @Test void rejectsGlobalDuplicateUuidAcrossDifferentRegionFiles() throws Exception {
        UUID uuid = UUID.randomUUID();
        region(0, 0, animal(uuid, "minecraft:cow", 0.5, 0.5, true));
        region(32, 0, animal(uuid, "minecraft:cow", 512.5, 0.5, true));
        IOException error = assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", true)));
        assertTrue(error.getMessage().contains("DUPLICATE_PERSISTED_UUID"));
    }

    @Test void includesPassengersInGlobalUniquenessAndExpectedReadback() throws Exception {
        UUID parent = UUID.randomUUID(), passenger = UUID.randomUUID();
        CompoundTag vehicle = animal(parent, "minecraft:cow", 15.5, 0.5, false);
        ListTag passengers = new ListTag();
        passengers.add(animal(passenger, "minecraft:chicken", 16.1, 0.5, false));
        vehicle.put("Passengers", passengers);
        region(0, 0, vehicle);
        assertEquals(2, audit(expect(parent, "minecraft:cow", false),
                expect(passenger, "minecraft:chicken", false)).observations().size());
        passengers.add(animal(parent, "minecraft:cow", 15.5, 0.5, false));
        region(0, 0, vehicle);
        assertThrows(IOException.class, () -> audit(expect(parent, "minecraft:cow", false)));
    }

    @Test void refusesMissingDeadForeignOrNonpersistentExpectedAnimals() throws Exception {
        UUID uuid = UUID.randomUUID();
        region(0, 0);
        assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", true)));
        CompoundTag tag = animal(uuid, "minecraft:cow", 0.5, 0.5, true);
        tag.putFloat("Health", 0); region(0, 0, tag);
        assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", true)));
        tag.putFloat("Health", 10); tag.getCompound("NeoForgeData").putString("proutlost_fauna_group", "foreign");
        region(0, 0, tag);
        assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", true)));
        tag.getCompound("NeoForgeData").putString("proutlost_fauna_group", "forest-01");
        tag.putBoolean("PersistenceRequired", false); region(0, 0, tag);
        assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", true)));
        // Baseline animals have their original persistence and carry no required owner tags.
        assertEquals(1, audit(expect(uuid, "minecraft:cow", false)).observations().size());
    }

    @Test void refusesUnexpectedOwnedUuidAndNonfiniteCoordinates() throws Exception {
        UUID uuid = UUID.randomUUID();
        region(0, 0, animal(uuid, "minecraft:cow", 0.5, 0.5, true));
        assertThrows(IOException.class, this::audit);
        region(0, 0, animal(uuid, "minecraft:cow", Double.NaN, 0.5, false));
        assertThrows(IOException.class, () -> audit(expect(uuid, "minecraft:cow", false)));
    }

    @Test void refusesTruncationUnsupportedFlagsAndWrongChunkIdentity() throws Exception {
        Files.write(dir.resolve("r.0.0.mca"), new byte[]{1});
        assertThrows(IOException.class, this::audit);
        Path path = region(0, 0);
        byte[] bytes = Files.readAllBytes(path); bytes[8196] = (byte)130; Files.write(path, bytes);
        assertThrows(IOException.class, this::audit);
        region(0, 0, animal(UUID.randomUUID(), "minecraft:cow", 32.5, 0.5, false));
        assertThrows(IOException.class, this::audit);
    }

    @Test void rejectsDuplicateExpectedUuidBeforeStartingAnyDiskTask() {
        UUID uuid = UUID.randomUUID();
        var expected = expect(uuid, "minecraft:cow", true);
        assertThrows(IllegalArgumentException.class, () ->
                new NativeFaunaModuleAudit(dir, BATCH, SHA, List.of(expected, expected)));
    }
}
