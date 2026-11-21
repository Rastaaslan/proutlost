package fr.proutlost.worldprep;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import fr.proutlost.Proutlost;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** A namespaced identifier with the historical quoted-string spelling retained. */
public final class WorldPrepIdArgument implements ArgumentType<String> {
    /** Load the game registry only from the real common mod-registration lifecycle. */
    private static final class Registration {
        private static final DeferredRegister<ArgumentTypeInfo<?,?>> TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE,Proutlost.MOD_ID);
        static {
            TYPES.register("worldprep_id",WorldPrepIdArgument::createInfo);
        }
    }
    public static void register(IEventBus modBus) {
        Registration.TYPES.register(modBus);
    }
    static SingletonArgumentInfo<WorldPrepIdArgument> createInfo() {
        return ArgumentTypeInfos.registerByClass(WorldPrepIdArgument.class,
            SingletonArgumentInfo.contextFree(WorldPrepIdArgument::new));
    }
    @Override public String parse(StringReader reader)throws CommandSyntaxException {
        int start=reader.getCursor();
        if(reader.canRead()&&reader.peek()=='\"') {
            String id=reader.readString();
            if(id.isBlank()||ResourceLocation.tryParse(id)==null) {
                reader.setCursor(start);
                throw new SimpleCommandExceptionType(
                    Component.literal("Invalid WorldPrep resource identifier")).createWithContext(reader);
            }
            return id;
        }
        ResourceLocationArgument.id().parse(reader);
        return reader.getString().substring(start,reader.getCursor());
    }
}
