package fr.proutlost.contract.director;

import java.util.*;

final class Checks {
    private Checks() {}
    static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
    static void text(String value) {
        text(value, Limits.MAX_STRING);
    }
    static void rowPayload(String value) {
        text(value, Limits.MAX_ROW_PAYLOAD_BYTES);
        require(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= Limits.MAX_ROW_PAYLOAD_BYTES, "Row payload byte cap");
    }
    static void text(String value, int maxChars) {
        require(value != null && !value.isEmpty() && value.length() <= maxChars, "String bound");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(++i < value.length() && Character.isLowSurrogate(value.charAt(i)), "Invalid Unicode");
            } else require(!Character.isLowSurrogate(c), "Invalid Unicode");
        }
    }
    static void nonnegative(long value, String field) { require(value >= 0, "Negative " + field); }
    static void duration(long value) { require(value >= 0 && value <= Limits.MAX_DURATION_MS, "Duration cap"); }
    static <T> List<T> copy(List<T> values) { size(values.size()); return List.copyOf(values); }
    static <T> Set<T> copy(Set<T> values) { size(values.size()); return Set.copyOf(values); }
    static <K,V> Map<K,V> copy(Map<K,V> values) { size(values.size()); return Map.copyOf(values); }
    static void size(int size) { require(size <= Limits.MAX_ITEMS, "Collection cap"); }
    static long volume(List<Bounds> bounds) {
        long total = 0;
        try {
            for (var b : bounds) {
                long x = (long)b.max().x() - b.min().x() + 1;
                long y = (long)b.max().y() - b.min().y() + 1;
                long z = (long)b.max().z() - b.min().z() + 1;
                total = Math.addExact(total, Math.multiplyExact(Math.multiplyExact(x, y), z));
            }
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Footprint overflow", e); }
        return total;
    }
}
