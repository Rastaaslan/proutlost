package fr.proutlost.quest.runtime;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Lossless UTF-8 values without StringTag's 65,535-byte modified-UTF limit.
 * Existing schema/key identities are retained; old StringTag saves remain readable. */
public final class SavedDataText {
    private SavedDataText() {}
    public static void put(CompoundTag root, String key, String text) {
        Objects.requireNonNull(text);
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            root.putByteArray(key, bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Invalid UTF-8 SavedData text: " + key, e);
        }
    }
    public static String get(CompoundTag root, String key) {
        if (root.contains(key, Tag.TAG_STRING)) return root.getString(key);
        if (!root.contains(key, Tag.TAG_BYTE_ARRAY))
            throw new IllegalStateException("Missing or unsupported SavedData text: " + key);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(root.getByteArray(key))).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Corrupt UTF-8 SavedData text: " + key, e);
        }
    }
}
