package fr.proutlost.worldprep;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage of actual argument metadata, without fabricating a NeoForge registry lifecycle.
 * Full packet registry-ID exchange is verified by the real client connection. */
class WorldPrepArgumentSerializationTest {
    private static void collect(CommandNode<CommandSourceStack> node,List<WorldPrepIdArgument> identifiers) {
        if(node instanceof ArgumentCommandNode<?,?> argument&&argument.getType() instanceof WorldPrepIdArgument id)
            identifiers.add(id);
        for(var child:node.getChildren())collect(child,identifiers);
    }
    @Test void productionIdentifierMetadataReconstructsAClientParserWithoutLosingBoundaries()throws Exception {
        // This is the same factory supplied to the real deferred registration.
        var info=WorldPrepIdArgument.createInfo();
        var dispatcher=new CommandDispatcher<CommandSourceStack>();
        WorldPrepCommands.registerTree(dispatcher);
        var identifiers=new ArrayList<WorldPrepIdArgument>();
        collect(dispatcher.getRoot(),identifiers);
        assertFalse(identifiers.isEmpty(),"exercise the real registered production command tree");
        for(var identifier:identifiers) {
            // The previous lambda fails this actual vanilla metadata lookup before player placement.
            assertSame(info,ArgumentTypeInfos.byClass(identifier));
            assertSame(info,ArgumentTypeInfos.unpack(identifier).type());
            var buffer=new FriendlyByteBuf(Unpooled.buffer());
            try {
                info.serializeToNetwork(info.unpack(identifier),buffer);
                var decoded=info.deserializeFromNetwork(buffer);
                assertSame(info,decoded.type());
                // contextFree deliberately ignores the registry context; full registry-ID exchange needs the real client.
                var restored=decoded.instantiate(null);
                assertEquals(WorldPrepIdArgument.class,restored.getClass());
                for(var spelling:List.of("proutlost:fixtures/tile-1","\"proutlost:fixtures/tile-1\"")) {
                    var reader=new StringReader(spelling+" 17");
                    assertEquals("proutlost:fixtures/tile-1",restored.parse(reader));
                    assertEquals(" 17",reader.getRemaining(),"next command argument remains unread");
                }
                assertEquals(0,buffer.readableBytes(),"metadata body consumed exactly");
            } finally {
                buffer.release();
            }
        }
    }
}
