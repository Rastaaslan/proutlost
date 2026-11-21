package fr.proutlost.quest.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;

/** Vanilla string argument keeps the command tree compatible with unmodified clients. */
public final class EvidenceArguments {
    private EvidenceArguments() {}
    public static StringArgumentType type() { return StringArgumentType.string(); }
    public static String quoted(String id) { return StringArgumentType.escapeIfRequired(id); }
    public static String documentId(UUID witness, String support, String location) {
        return witness + ":" + support + ":" + location;
    }
}
