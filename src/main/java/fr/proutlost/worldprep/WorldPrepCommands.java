package fr.proutlost.worldprep;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class WorldPrepCommands {
    private WorldPrepCommands() {}
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("proutlost")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("worldprep")
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("WorldPrep " + (WorldPrepConfig.ENABLED.get() ? "enabled" : "disabled") + " (" + WorldPrepConfig.MODE.get() + ")"), false);
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("location")
                .then(Commands.literal("validate").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Location registry validation requested"), false);
                    return Command.SINGLE_SUCCESS;
                }))));
    }
}
