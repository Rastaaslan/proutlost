package fr.proutlost.quest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.proutlost.quest.runtime.EvidenceArguments;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.*;

class CommandParsingTest {
    @Test void realDocumentIdsRoundTripThroughBrigadierShareAndComparison() throws Exception {
        String first=EvidenceArguments.documentId(UUID.randomUUID(),"wreck_motif","epave");
        String second=EvidenceArguments.documentId(UUID.randomUUID(),"camp_motif","ancien_camp");
        var parsed=new ArrayList<String>(); var dispatcher=new CommandDispatcher<Object>();
        dispatcher.register(literal("share").then(argument("observation",EvidenceArguments.type())
            .then(argument("recipient",StringArgumentType.word()).executes(c->{
                parsed.add(StringArgumentType.getString(c,"observation")); return 1;
            }))));
        dispatcher.register(literal("compare_prepare").then(argument("first",EvidenceArguments.type())
            .then(argument("second",EvidenceArguments.type()).executes(c->{
                parsed.add(StringArgumentType.getString(c,"first")); parsed.add(StringArgumentType.getString(c,"second")); return 1;
            }))));
        assertEquals(1,dispatcher.execute("share "+EvidenceArguments.quoted(first)+" Player",new Object()));
        assertEquals(1,dispatcher.execute("compare_prepare "+EvidenceArguments.quoted(first)+" "+EvidenceArguments.quoted(second),new Object()));
        assertEquals(List.of(first,first,second),parsed);
    }
    @Test void legacyColonIdsRemainSupportedAndMalformedQuotedInputFails() throws Exception {
        var reader=new com.mojang.brigadier.StringReader(EvidenceArguments.quoted("legacy:id:with:colons"));
        assertEquals("legacy:id:with:colons",EvidenceArguments.type().parse(reader));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            ()->EvidenceArguments.type().parse(new com.mojang.brigadier.StringReader("\"unterminated")));
    }
}
