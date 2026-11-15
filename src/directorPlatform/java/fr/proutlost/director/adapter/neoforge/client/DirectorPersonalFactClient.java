package fr.proutlost.director.adapter.neoforge.client;

import fr.proutlost.director.adapter.neoforge.network.DirectorPayloads;
import fr.proutlost.director.adapter.neoforge.network.DirectorPersonalFactReceipts;
import fr.proutlost.director.api.model.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Explicit return of a server-issued personal RECORD envelope; no client fact or rendering claim. */
public final class DirectorPersonalFactClient {
    private DirectorPersonalFactClient(){}
    public static LiteralArgumentBuilder<CommandSourceStack> command(Supplier<SessionIdentity> identity,
            Supplier<DirectorPayloads> channels,BooleanSupplier suspended){
        var world=Commands.argument("world",StringArgumentType.word())
            .executes(c->record(c,identity,channels,suspended,Optional.empty()))
            .then(Commands.argument("text",StringArgumentType.greedyString())
                .executes(c->record(c,identity,channels,suspended,Optional.of(StringArgumentType.getString(c,"text")))));
        var sequence=Commands.argument("sequence",LongArgumentType.longArg(0))
            .then(Commands.argument("ttl",IntegerArgumentType.integer(0,1200))
                .then(Commands.argument("boot",LongArgumentType.longArg(0))
                    .then(Commands.argument("connection",LongArgumentType.longArg(0)).then(world))));
        return Commands.literal("recordfact")
            .then(Commands.argument("fact",StringArgumentType.word())
                .then(Commands.argument("instance",StringArgumentType.word())
                    .then(Commands.argument("effect",StringArgumentType.word()).then(sequence))));
    }
    private static int record(CommandContext<CommandSourceStack> context,Supplier<SessionIdentity> identity,
            Supplier<DirectorPayloads> channels,BooleanSupplier suspended,Optional<String> note){
        var mc=Minecraft.getInstance();
        if(!mc.isSameThread())throw new IllegalStateException("J06 client actor required");
        var source=context.getSource();var current=identity.get();
        if(suspended.getAsBoolean()||current==null||channels.get()==null||mc.level==null||mc.player==null
            ||!current.playerId().equals(new PlayerId(mc.player.getUUID()))
            ||!current.dimension().equals(new DimensionId(mc.level.dimension().location().toString()))){
            source.sendFailure(Component.literal("Relevé indisponible : session actuelle requise."));return 0;
        }
        try{
            var fact=new FactId(UUID.fromString(StringArgumentType.getString(context,"fact")));
            var world=new WorldId(UUID.fromString(StringArgumentType.getString(context,"world")));
            var boot=new Epoch(LongArgumentType.getLong(context,"boot"));
            var connection=new Epoch(LongArgumentType.getLong(context,"connection"));
            if(!DirectorPersonalFactReceipts.current(current,world,boot,connection)){
                source.sendFailure(Component.literal("Cette référence appartient à une ancienne session ; rouvrez le journal."));return 0;
            }
            var header=new PerceptionHeader(1,world,boot,connection,current.dimension(),
                new EventInstanceId(UUID.fromString(StringArgumentType.getString(context,"instance"))),
                new EffectId(UUID.fromString(StringArgumentType.getString(context,"effect"))),
                new Sequence(LongArgumentType.getLong(context,"sequence")),
                new DurationTicks(IntegerArgumentType.getInteger(context,"ttl")));
            channels.get().sendToServer(new RecordObservation(header,new RecordObservationPayload(
                Optional.empty(),Optional.of(fact),note.filter(t->!t.isBlank()))));
            source.sendSuccess(()->Component.literal("Référence envoyée ; confirmation du serveur en attente."),false);return 1;
        }catch(RuntimeException unavailable){
            source.sendFailure(Component.literal("Relevé indisponible : référence reçue et texte borné requis."));return 0;
        }
    }
}
