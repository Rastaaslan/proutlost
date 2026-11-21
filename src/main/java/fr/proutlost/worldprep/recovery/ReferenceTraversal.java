package fr.proutlost.worldprep.recovery;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Keyset pagination: at most 64 metadata rows examined, no history copy or sort.
 * A cursor is a position in live metadata, not a snapshot certificate. Insertions before
 * that position require a fresh audit; missing history is never reconstructed.
 */
public final class ReferenceTraversal {
    public interface Source {
        Row higher(String after);
    }
    public record Row(String key, ReferenceInventory.Reference reference) {}
    public record Page(List<ReferenceInventory.Reference> references, String next, int examined) {
        public Page { references = List.copyOf(references); }
    }
    public static <K,V> Source source(NavigableMap<K,V> index, Function<String,K> decode,
            Function<K,String> encode, Function<Map.Entry<K,V>,ReferenceInventory.Reference> reference) {
        return after -> {
            var entry = after == null ? index.firstEntry() : index.higherEntry(decode.apply(after));
            return entry == null ? null : new Row(encode.apply(entry.getKey()), reference.apply(entry));
        };
    }
    public static Page page(List<Source> sources, String cursor) {
        if (sources.size() > 4) throw new IllegalArgumentException("At most four reference indexes");
        int phase = 0;
        String after = null;
        if (!cursor.equals("start") && !cursor.equals("0")) {
            if (cursor.length() > 4096) throw new IllegalArgumentException("Oversized audit cursor");
            var fields = cursor.split("\\.", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid audit cursor");
            phase = Integer.parseInt(fields[0]);
            if (phase < 0 || phase >= sources.size()) throw new IllegalArgumentException("Invalid audit index");
            if (!fields[1].isEmpty()) after = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
        }
        var result = new ArrayList<ReferenceInventory.Reference>(64);
        int examined = 0;
        while (phase < sources.size() && examined < 64) {
            var row = sources.get(phase).higher(after);
            if (row == null) { phase++; after = null; continue; }
            examined++;
            after = row.key();
            if (row.reference() != null) result.add(row.reference());
        }
        String next = phase == sources.size() ? "DONE" : phase + "." +
                (after == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(after.getBytes(StandardCharsets.UTF_8)));
        if (next.length() > 4096) throw new IllegalArgumentException("Reference key exceeds bounded cursor");
        return new Page(result, next, examined);
    }
    private ReferenceTraversal() {}
}
