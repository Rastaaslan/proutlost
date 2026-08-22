package fr.proutlost.worldprep;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.runtime.MinecraftTerrainSampler;
import fr.proutlost.worldprep.runtime.BlockMutationRuntime;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;

public final class WorldPrepCommands {
    private WorldPrepCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var areaArg=Commands.argument("area",StringArgumentType.word());
        event.getDispatcher().register(Commands.literal("proutlost").requires(s->s.hasPermission(2)).then(Commands.literal("worldprep")
            .then(Commands.literal("pos1").executes(c->position(c.getSource(),true)))
            .then(Commands.literal("pos2").executes(c->position(c.getSource(),false)))
            .then(Commands.literal("area")
                .then(Commands.literal("create").then(Commands.argument("id",StringArgumentType.word()).executes(c->createArea(c.getSource(),StringArgumentType.getString(c,"id")))))
                .then(Commands.literal("info").then(Commands.argument("id",StringArgumentType.word()).executes(c->areaInfo(c.getSource(),StringArgumentType.getString(c,"id")))))
                .then(Commands.literal("list").executes(c->listAreas(c.getSource())))
                .then(Commands.literal("delete").then(Commands.argument("id",StringArgumentType.word()).executes(c->deleteArea(c.getSource(),StringArgumentType.getString(c,"id"))))))
            .then(Commands.literal("analyze").then(areaArg.executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ANALYZE,StringArgumentType.getString(c,"area")))))
            .then(Commands.literal("preview").then(Commands.literal("biomes").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_BIOMES,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("geology").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_GEOLOGY,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("ores").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_ORES,StringArgumentType.getString(c,"area"))))))
            .then(Commands.literal("apply").then(Commands.literal("biomes").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_BIOMES,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("geology").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_GEOLOGY,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("ores").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_ORES,StringArgumentType.getString(c,"area"))))))
            .then(Commands.literal("rollback").then(Commands.literal("biomes").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_BIOMES,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("geology").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,StringArgumentType.getString(c,"area")))))
                .then(Commands.literal("ores").then(Commands.argument("area",StringArgumentType.word()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_ORES,StringArgumentType.getString(c,"area"))))))
            .then(Commands.literal("inspect").executes(c->inspect(c.getSource())))
            .then(Commands.literal("status").executes(c->status(c.getSource())))
            .then(Commands.literal("pause").executes(c->control(c.getSource(),"pause")))
            .then(Commands.literal("resume").executes(c->control(c.getSource(),"resume")))
            .then(Commands.literal("cancel").executes(c->control(c.getSource(),"cancel"))))
            .then(Commands.literal("location").then(Commands.literal("validate").executes(c->{c.getSource().sendSuccess(()->Component.literal("Location validation has no conflicts in the biome vertical slice"),false);return Command.SINGLE_SUCCESS;}))));
    }
    private static int position(CommandSourceStack s,boolean first)throws CommandSyntaxException{ServerPlayer p=s.getPlayerOrException();ServerLevel l=p.serverLevel();var d=WorldPrepRuntime.data(l);var old=d.selections().getOrDefault(p.getUUID(),new WorldPrepSavedData.Selection(null,null,null,null));var pos=p.blockPosition();d.selections().put(p.getUUID(),first?new WorldPrepSavedData.Selection(pos.getX(),pos.getZ(),old.x2(),old.z2()):new WorldPrepSavedData.Selection(old.x1(),old.z1(),pos.getX(),pos.getZ()));d.changed();s.sendSuccess(()->Component.literal((first?"pos1":"pos2")+" = "+pos.getX()+", "+pos.getZ()),false);return Command.SINGLE_SUCCESS;}
    private static int createArea(CommandSourceStack s,String id)throws CommandSyntaxException{if(ResourceLocation.tryParse(id)==null)throw new IllegalArgumentException("Area ID must be namespaced");ServerPlayer p=s.getPlayerOrException();var d=WorldPrepRuntime.data(p.serverLevel());var sel=d.selections().get(p.getUUID());if(sel==null||sel.x1()==null||sel.x2()==null)throw new IllegalStateException("Set pos1 and pos2 first");var a=new WorldPrepSavedData.Area(id,p.serverLevel().dimension().location().toString(),Math.min(sel.x1(),sel.x2()),Math.min(sel.z1(),sel.z2()),Math.max(sel.x1(),sel.x2()),Math.max(sel.z1(),sel.z2()));if(d.areas().putIfAbsent(id,a)!=null)throw new IllegalStateException("Area already exists");d.changed();s.sendSuccess(()->Component.literal("Created "+id),false);return Command.SINGLE_SUCCESS;}
    private static int areaInfo(CommandSourceStack s,String id){var a=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level(s)),id);s.sendSuccess(()->Component.literal(a.id()+" ["+a.minX()+","+a.minZ()+" -> "+a.maxX()+","+a.maxZ()+"] "+a.dimension()),false);return 1;}
    private static int listAreas(CommandSourceStack s){var d=WorldPrepRuntime.data(level(s));d.areas().values().forEach(a->s.sendSuccess(()->Component.literal(a.id()),false));return d.areas().size();}
    private static int deleteArea(CommandSourceStack s,String id){var d=WorldPrepRuntime.data(level(s));if(d.areas().remove(id)==null)throw new IllegalArgumentException("Unknown area");d.changed();return 1;}
    private static int enqueue(CommandSourceStack s,WorldPrepSavedData.Operation op,String area){var id=WorldPrepRuntime.enqueue(level(s),op,area);s.sendSuccess(()->Component.literal("Queued "+op+" job "+id),false);return 1;}
    private static int inspect(CommandSourceStack s)throws CommandSyntaxException{var p=s.getPlayerOrException();var pos=p.blockPosition();var sample=MinecraftTerrainSampler.sample(p.serverLevel(),pos.getX(),pos.getZ());String current=p.serverLevel().getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse("unregistered");s.sendSuccess(()->Component.literal("Surface="+sample.surfaceY()+" waterDepth="+sample.waterDepth()+" slope="+sample.slope()+" roughness="+sample.roughness()+" coast="+sample.coastDistance()+" biome="+current),false);var blocks=BlockMutationRuntime.data(p.serverLevel());blocks.plans().values().forEach(plan->s.sendSuccess(()->Component.literal(plan.pass+" area="+plan.area+" profile="+plan.profile+" eligible="+plan.eligible+" skipped="+plan.skipped+" replacements="+plan.changes.size()+" fingerprint="+plan.fingerprint),false));return 1;}
    private static int status(CommandSourceStack s){var d=WorldPrepRuntime.data(level(s));if(d.jobs().isEmpty())s.sendSuccess(()->Component.literal("No WorldPrep jobs"),false);d.jobs().values().forEach(j->s.sendSuccess(()->Component.literal(j.id()+" "+j.operation()+" "+j.state()+" cursor="+j.cursor()+(j.error().isEmpty()?"":" error="+j.error())),false));return d.jobs().size();}
    private static int control(CommandSourceStack s,String action){var d=WorldPrepRuntime.data(level(s));var e=d.jobs().entrySet().stream().filter(x->x.getValue().state()==WorldPrepSavedData.JobState.RUNNING||x.getValue().state()==WorldPrepSavedData.JobState.QUEUED||x.getValue().state()==WorldPrepSavedData.JobState.PAUSED).findFirst().orElseThrow(()->new IllegalStateException("No controllable job"));var j=e.getValue();var state=switch(action){case "pause"->{if(j.state()!=WorldPrepSavedData.JobState.RUNNING)throw new IllegalStateException("Job is not running");yield WorldPrepSavedData.JobState.PAUSED;}case "resume"->{if(j.state()!=WorldPrepSavedData.JobState.PAUSED)throw new IllegalStateException("Job is not paused");yield WorldPrepSavedData.JobState.RUNNING;}default->WorldPrepSavedData.JobState.CANCELLED;};e.setValue(j.withState(state));d.changed();return 1;}
    private static ServerLevel level(CommandSourceStack s){return s.getLevel();}
}
