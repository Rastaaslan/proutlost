package fr.proutlost.director.adapter.neoforge.client;

import fr.proutlost.director.adapter.neoforge.network.DirectorAnchorInteractionCommands;
import fr.proutlost.director.adapter.neoforge.network.DirectorPayloads;
import fr.proutlost.director.api.model.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.function.*;

/** Physical-client button producer for the existing 1.1.0 AnchorInteraction C2S channel. */
public final class DirectorAnchorInteractionClient {
    private DirectorAnchorInteractionClient(){}
    public static LiteralArgumentBuilder<CommandSourceStack> command(Supplier<SessionIdentity> identity,
            Supplier<DirectorPayloads> channels,BooleanSupplier suspended,
            Function<DirectorAnchorInteractionCommands.Context,PerceptionHeader> received) {
        return Commands.literal("select")
            .then(Commands.argument("instance",StringArgumentType.word())
                .then(Commands.argument("action",StringArgumentType.word())
                    .then(Commands.argument("anchor",StringArgumentType.word())
                        .then(Commands.argument("effect",StringArgumentType.word())
                            .then(Commands.argument("sequence",LongArgumentType.longArg(0))
                                .executes(c->select(c,identity,channels,suspended,received)))))));
    }
    private static int select(CommandContext<CommandSourceStack> context,Supplier<SessionIdentity> identity,
            Supplier<DirectorPayloads> channels,BooleanSupplier suspended,
            Function<DirectorAnchorInteractionCommands.Context,PerceptionHeader> received) {
        var mc=Minecraft.getInstance();
        if(!mc.isSameThread())throw new IllegalStateException("J06 client actor required");
        var source=context.getSource();var current=identity.get();var channel=channels.get();
        if(suspended.getAsBoolean()||current==null||channel==null||mc.level==null||mc.player==null
            ||!current.playerId().equals(new PlayerId(mc.player.getUUID()))
            ||!current.dimension().equals(new DimensionId(mc.level.dimension().location().toString()))) {
            source.sendFailure(Component.literal("Interaction indisponible : session actuelle requise."));return 0;
        }
        try {
            var reference=DirectorAnchorInteractionCommands.parse(StringArgumentType.getString(context,"instance"),
                StringArgumentType.getString(context,"anchor"),StringArgumentType.getString(context,"effect"),
                LongArgumentType.getLong(context,"sequence"));
            var interaction=DirectorAnchorInteractionCommands.interaction(current,received.apply(reference),
                reference,DirectorAnchorInteractionCommands.action(StringArgumentType.getString(context,"action")));
            channel.sendToServer(interaction);
            source.sendSuccess(()->Component.literal("Interaction envoyée ; confirmation du serveur en attente."),false);
            return 1;
        } catch(RuntimeException refused) {
            source.sendFailure(Component.literal("Interaction indisponible : offre actuelle et perception reçue requises."));return 0;
        }
    }
}
