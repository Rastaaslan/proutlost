package fr.proutlost.worldprep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorldPrepCommandParsingTest {
    private CommandDispatcher<CommandSourceStack> parser(){
        var registered=new CommandDispatcher<CommandSourceStack>();
        WorldPrepCommands.registerTree(registered);
        var parser=new CommandDispatcher<CommandSourceStack>();
        // Parse the production grammar without requiring a live permission-bearing server source.
        // Never execute its handlers. Permission enforcement is covered by the runtime GameTest.
        for(var child:registered.getRoot().getChildren())parser.getRoot().addChild(withoutRequirements(child));
        return parser;
    }
    private CommandNode<CommandSourceStack> withoutRequirements(CommandNode<CommandSourceStack> node){
        var copy=node.createBuilder().requires(source->true).build();
        for(var child:node.getChildren())copy.addChild(withoutRequirements(child));
        return copy;
    }
    @Test void everyAreaCommandAcceptsQuotedAndUnquotedNamespacedIdentifiers(){
        var parser=parser();
        var commands=new ArrayList<>(List.of("area create","area info","area delete","analyze","discover","batch","preview structures"));
        for(var pass:List.of("biomes","geology","ores","soils","flora","trees","aquatic")){
            commands.add("preview "+pass);commands.add("apply "+pass);
        }
        for(var command:commands)for(var identifier:List.of("proutlost:fixtures/tile-1","\"proutlost:fixtures/tile-1\"")){
            String input="proutlost worldprep "+command+" "+identifier;
            var result=parser.parse(input,null);
            assertFalse(result.getReader().canRead(),input);
            assertTrue(result.getExceptions().isEmpty(),input);
            assertNotNull(result.getContext().getCommand(),input);
            assertEquals("proutlost:fixtures/tile-1",StringArgumentType.getString(result.getContext().build(input),
                    command.startsWith("area ")?"id":"area"),input);
        }
    }
    @Test void quotedAreaDoesNotConsumeBoundsOrRollbackSnapshot(){
        var parser=parser();
        String define="proutlost worldprep area define \"proutlost:fixture\" 4496 -1666 5236 -943";
        var result=parser.parse(define,null);
        assertFalse(result.getReader().canRead());assertTrue(result.getExceptions().isEmpty());
        var context=result.getContext().build(define);
        assertEquals("proutlost:fixture",StringArgumentType.getString(context,"id"));
        assertEquals(-1666,context.getArgument("minZ",Integer.class));
        assertEquals(-943,context.getArgument("maxZ",Integer.class));
        var snapshot=UUID.randomUUID();
        for(var pass:List.of("biomes","geology","ores","soils","flora","trees","aquatic")){
            String input="proutlost worldprep rollback "+pass+" \"proutlost:fixture\" "+snapshot;
            var parsed=parser.parse(input,null);
            assertFalse(parsed.getReader().canRead(),input);assertTrue(parsed.getExceptions().isEmpty(),input);
            assertEquals(snapshot,parsed.getContext().build(input).getArgument("snapshot",UUID.class));
        }
    }
    @Test void explicitRecoveryRequiresExactJobUuid() {
        var parser=parser(); var id=UUID.randomUUID();
        String input="proutlost worldprep job "+id+" recover";
        var result=parser.parse(input,null);
        assertFalse(result.getReader().canRead());
        assertTrue(result.getExceptions().isEmpty());
        assertNotNull(result.getContext().getCommand());
        assertEquals(id,result.getContext().build(input).getArgument("id",UUID.class));
        var invalid=parser.parse(input+" extra",null);
        assertTrue(invalid.getReader().canRead()||!invalid.getExceptions().isEmpty());
    }
    @Test void malformedQuotesAndTrailingTokensAreRejectedAndSimpleIdsRemainAccepted(){
        var parser=parser();
        for(var suffix:List.of("\"proutlost:unterminated","proutlost:invalid?path","\"proutlost:fixture\" extra")){
            var parsed=parser.parse("proutlost worldprep analyze "+suffix,null);
            assertTrue(parsed.getReader().canRead()||!parsed.getExceptions().isEmpty(),suffix);
        }
        var simple=parser.parse("proutlost worldprep area info fixture",null);
        assertFalse(simple.getReader().canRead());assertTrue(simple.getExceptions().isEmpty());
        assertNotNull(simple.getContext().getCommand());
    }
    @Test void structuresRequiresAreaAndReferenceAuditAcceptsKeysetCursor() {
        var parser=parser();
        assertNull(parser.parse("proutlost worldprep apply structures",null).getContext().getCommand());
        for(String cursor:List.of("start","0","2.MDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAw")) {
            String input="proutlost worldprep audit-references "+cursor;
            var parsed=parser.parse(input,null);
            assertFalse(parsed.getReader().canRead());assertTrue(parsed.getExceptions().isEmpty());
            assertNotNull(parsed.getContext().getCommand());
            assertEquals(cursor,StringArgumentType.getString(parsed.getContext().build(input),"cursor"));
        }
    }

    @Test void exactTemplateCommandRequiresExplicitAdapterAndTransform() {
        var parser=parser();
        String input="proutlost worldprep apply structures \"proutlost:fixture\" exact-static-template-v1 proutlost:qualification/platform 64 80 64 CLOCKWISE_90 LEFT_RIGHT";
        var parsed=parser.parse(input,null);
        assertFalse(parsed.getReader().canRead());assertTrue(parsed.getExceptions().isEmpty());assertNotNull(parsed.getContext().getCommand());
        var opaque=parser.parse(input.replace("exact-static-template-v1","opaque"),null);
        assertTrue(opaque.getReader().canRead());
    }

    @Test void standaloneFaunaControlsHaveExplicitGrammarAndBindRetainedBatchUuid() {
        var parser=parser();
        var batch=UUID.fromString("919f62c4-505c-430c-9f00-f5edc5c48dab");
        for(String suffix:List.of("status","preview","start","pause","resume","audit",
                "resume "+batch,"audit "+batch)) {
            String input="proutlost worldprep fauna-module "+suffix;
            var parsed=parser.parse(input,null);
            assertFalse(parsed.getReader().canRead(),input);
            assertTrue(parsed.getExceptions().isEmpty(),input);
            assertNotNull(parsed.getContext().getCommand(),input);
            if(suffix.contains(" "))assertEquals(batch,parsed.getContext().build(input).getArgument("batch",UUID.class));
        }
        assertNull(parser.parse("proutlost worldprep fauna-module",null).getContext().getCommand());
        for(String suffix:List.of("start "+batch,"resume not-a-uuid","audit not-a-uuid",
                "audit "+batch+" extra","status extra","force","respawn")) {
            var parsed=parser.parse("proutlost worldprep fauna-module "+suffix,null);
            assertTrue(parsed.getReader().canRead()||!parsed.getExceptions().isEmpty(),suffix);
        }
    }

}
