package fr.proutlost.worldprep.physical;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.util.*;

public final class NativePhysicalCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> structures() {
        return Commands.literal("structures").then(Commands.argument("area",StringArgumentType.string())
            .then(Commands.literal(NativeStructurePass.ADAPTER)
            .then(Commands.argument("template",ResourceLocationArgument.id())
            .then(Commands.argument("origin",BlockPosArgument.blockPos())
            .then(Commands.argument("rotation",StringArgumentType.word())
            .then(Commands.argument("mirror",StringArgumentType.word()).executes(c->run(c.getSource(),()->
                NativePhysicalRuntime.structures(c.getSource().getLevel(),StringArgumentType.getString(c,"area"),
                    ResourceLocationArgument.getId(c,"template").toString(),BlockPosArgument.getBlockPos(c,"origin"),
                    Rotation.valueOf(StringArgumentType.getString(c,"rotation")),Mirror.valueOf(StringArgumentType.getString(c,"mirror")))))))))));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> fauna() {
        return Commands.literal("fauna").then(Commands.argument("area",StringArgumentType.string())
            .then(Commands.argument("species",ResourceLocationArgument.id())
            .then(Commands.argument("origin",BlockPosArgument.blockPos())
            .then(Commands.argument("count",IntegerArgumentType.integer(1,8)).executes(c->run(c.getSource(),()-> {
                var level=c.getSource().getLevel();
                Path file=level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldprep-fauna-rules.json");
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536)throw new IllegalArgumentException("Missing/bounded fauna rules file required");
                var json=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(json.get("schemaVersion").getAsInt()!=1)throw new IllegalArgumentException("Unknown fauna rules version");
                var id=ResourceLocationArgument.getId(c,"species").toString();
                var rule=new com.google.gson.Gson().fromJson(json.getAsJsonObject("rules").get(id),NativeFaunaPass.Rule.class);
                if(rule==null||!rule.species().equals(id))throw new IllegalArgumentException("Species not explicitly configured");
                var origin=BlockPosArgument.getBlockPos(c,"origin");var positions=new ArrayList<net.minecraft.core.BlockPos>();
                for(int i=0;i<IntegerArgumentType.getInteger(c,"count");i++)positions.add(origin.offset(Math.multiplyExact(i,rule.separation()),0,0));
                return NativePhysicalRuntime.fauna(level,StringArgumentType.getString(c,"area"),rule,positions);
            }))))));
    }
    /** Standalone structure module: never starts ecology/RUN55 or V5. Explicit admin-only. */
    public static LiteralArgumentBuilder<CommandSourceStack> structureModule() {
        return Commands.literal("structure-module").requires(s->s.hasPermission(4))
            .then(Commands.literal("status").executes(c->{
                c.getSource().sendSuccess(()->Component.literal(WorldPrepStructureModule.status(c.getSource().getLevel())),false);
                return 1;
            }))
            // Reads the configured generator/registries only; never requests a chunk.
            .then(Commands.literal("seed-biome")
                .then(Commands.argument("x",IntegerArgumentType.integer())
                .then(Commands.argument("y",IntegerArgumentType.integer())
                .then(Commands.argument("z",IntegerArgumentType.integer()).executes(c->run(c.getSource(),()->{
                    var level=c.getSource().getLevel();
                    NativePhysicalRuntime.requireThread(level);
                    int x=IntegerArgumentType.getInteger(c,"x");
                    int y=IntegerArgumentType.getInteger(c,"y");
                    int z=IntegerArgumentType.getInteger(c,"z");
                    var pos=new net.minecraft.core.BlockPos(x,y,z);
                    if(!level.isInWorldBounds(pos)||!level.getWorldBorder().isWithinBounds(pos))
                        throw new IllegalArgumentException("Seed-biome position outside current world/build bounds");
                    int qx=Math.floorDiv(x,4),qy=Math.floorDiv(y,4),qz=Math.floorDiv(z,4);
                    var chunks=level.getChunkSource();
                    var biome=chunks.getGenerator().getBiomeSource().getNoiseBiome(
                        qx,qy,qz,chunks.randomState().sampler());
                    var key=biome.unwrapKey().orElseThrow(
                        ()->new IllegalStateException("Seed biome has no registry key")).location();
                    var result=new com.google.gson.JsonObject();
                    result.addProperty("dimension",level.dimension().location().toString());
                    result.addProperty("x",x);result.addProperty("y",y);result.addProperty("z",z);
                    result.addProperty("quartX",qx);result.addProperty("quartY",qy);result.addProperty("quartZ",qz);
                    result.addProperty("biome",key.toString());
                    result.addProperty("source","CURRENT_GENERATOR_SEED_BIOME_NO_CHUNK_ACCESS");
                    c.getSource().sendSuccess(()->Component.literal("WorldPrep seed-biome "+result),false);
                    return 1;
                }))))))
            .then(Commands.literal("preview").executes(c->run(c.getSource(),()->{
                var plans=WorldPrepStructureModule.preview(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal(
                    "WorldPrep structures-only preview: "+plans.size()+" planned; NOT_PLACED; NOT_DIRECTOR_IMPORTED"),false);
                return plans.size();
            })))
            .then(Commands.literal("start").executes(c->run(c.getSource(),()->{
                var id=WorldPrepStructureModule.start(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal(
                    "WorldPrep structures-only started: "+id+"; no ecology/V5; NOT_CERTIFIED"),false);
                return 1;
            })))
            .then(Commands.literal("export-director").executes(c->run(c.getSource(),()->{
                var source=c.getSource();var server=source.getServer();
                DirectorStructureExportBridge.exportAsync(source.getLevel()).whenComplete((result,error)->
                    server.execute(()->{
                        if(error!=null)source.sendFailure(Component.literal("WorldPrep Director export not confirmed: "+error.getClass().getSimpleName()));
                        else source.sendSuccess(()->Component.literal(new com.google.gson.Gson().toJson(result)),false);
                    }));
                source.sendSuccess(()->Component.literal("WorldPrep Director export queued; NOT_DIRECTOR_IMPORTED"),false);
                return 1;
            })))
            .then(Commands.literal("pause").executes(c->{
                WorldPrepStructureModule.pause(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal(
                    "Standalone structure-module paused; NO AUTOMATIC RETRY"),false);
                return 1;
            }));
    }
    /** Explicit operator-sited configured features, independent of ecology. */
    public static LiteralArgumentBuilder<CommandSourceStack> featureModule() {
        return Commands.literal("feature-module").requires(s->s.hasPermission(4))
            .then(Commands.literal("status").executes(c->{
                c.getSource().sendSuccess(()->Component.literal(WorldPrepFeatureModule.status(c.getSource().getLevel())),false);
                return 1;
            }))
            .then(Commands.literal("preview").executes(c->run(c.getSource(),()->{
                String result=WorldPrepFeatureModule.preview(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal(result),false);return 1;
            })))
            .then(Commands.literal("start").executes(c->run(c.getSource(),()->{
                var id=WorldPrepFeatureModule.start(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal("WorldPrep configured features started: "+id+"; capture before mutation; NOT_DIRECTOR_IMPORTED"),false);
                return 1;
            })))
            .then(Commands.literal("pause").executes(c->{
                WorldPrepFeatureModule.pause(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal("Configured-feature module paused; no automatic retry"),false);
                return 1;
            }));
    }
    /** Independently callable native fauna population and disk audit; never starts ecology. */
    public static LiteralArgumentBuilder<CommandSourceStack> faunaModule() {
        return Commands.literal("fauna-module").requires(s->s.hasPermission(4))
            .then(Commands.literal("status").executes(c->run(c.getSource(),()->{
                c.getSource().sendSuccess(()->Component.literal(WorldPrepFaunaModule.status(c.getSource().getLevel())),false);
                return 1;
            })))
            .then(Commands.literal("preview").executes(c->run(c.getSource(),()->{
                String result=WorldPrepFaunaModule.preview(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal(result),false);
                return 1;
            })))
            .then(Commands.literal("start").executes(c->run(c.getSource(),()->{
                var batch=WorldPrepFaunaModule.start(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna module started: "+batch+"; awaiting saved-entity audit"),false);
                return 1;
            })))
            .then(Commands.literal("resume")
                .executes(c->run(c.getSource(),()->{
                    var batch=WorldPrepFaunaModule.resume(c.getSource().getLevel());
                    c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna module resumed: "+batch),false);return 1;
                }))
                .then(Commands.argument("batch",UuidArgument.uuid()).executes(c->run(c.getSource(),()->{
                    var batch=WorldPrepFaunaModule.resume(c.getSource().getLevel(),UuidArgument.getUuid(c,"batch"));
                    c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna module resumed: "+batch),false);return 1;
                }))))
            .then(Commands.literal("audit")
                .executes(c->run(c.getSource(),()->{
                    var batch=WorldPrepFaunaModule.audit(c.getSource().getLevel());
                    c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna disk audit started: "+batch+"; no population replay"),false);return 1;
                }))
                .then(Commands.argument("batch",UuidArgument.uuid()).executes(c->run(c.getSource(),()->{
                    var batch=WorldPrepFaunaModule.audit(c.getSource().getLevel(),UuidArgument.getUuid(c,"batch"));
                    c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna disk audit started: "+batch+"; no population replay"),false);return 1;
                }))))
            .then(Commands.literal("pause").executes(c->run(c.getSource(),()->{
                WorldPrepFaunaModule.pause(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal("WorldPrep fauna module paused; explicit resume required"),false);
                return 1;
            })));
    }
    /** Explicit audit of an already placed native batch; never regenerates it. */
    public static LiteralArgumentBuilder<CommandSourceStack> nativeReconciliation() {
        return Commands.literal("native-reconciliation").requires(s->s.hasPermission(4))
            .then(Commands.literal("status").executes(c->{
                c.getSource().sendSuccess(()->Component.literal(NativeStructureReconciliation.status(c.getSource().getLevel())),false);
                return 1;
            }))
            .then(Commands.literal("start").then(Commands.argument("batch",UuidArgument.uuid()).executes(c->run(c.getSource(),()->{
                var id=NativeStructureReconciliation.start(c.getSource().getLevel(),UuidArgument.getUuid(c,"batch"));
                c.getSource().sendSuccess(()->Component.literal("Native audit-only reconciliation started: "+id+"; NO_GENERATION_REPLAY"),false);
                return 1;
            }))))
            .then(Commands.literal("pause").executes(c->{
                NativeStructureReconciliation.pause(c.getSource().getLevel());
                c.getSource().sendSuccess(()->Component.literal("Native audit-only reconciliation paused"),false);
                return 1;
            }));
    }
    private static int run(CommandSourceStack source,java.util.concurrent.Callable<Integer> operation) {
        try{return operation.call();}
        catch(Exception failure){
            source.sendFailure(Component.literal("WorldPrep physical module refused: "+failure.getMessage()));
            return 0;
        }
    }
    public static LiteralArgumentBuilder<CommandSourceStack> control() {
        var root=Commands.literal("physical");
        root.then(Commands.literal("survey-export").requires(s->s.hasPermission(4))
            .then(Commands.argument("request",StringArgumentType.word()).executes(c->run(c.getSource(),()->{
                var result=NativeObservedPhysicalCatalogExport.export(c.getSource().getLevel(),StringArgumentType.getString(c,"request"));
                c.getSource().sendSuccess(()->Component.literal(new com.google.gson.Gson().toJson(result)),false);
                return result.targets();
            }))));

        root.then(Commands.literal("survey-director").requires(source->source.hasPermission(4)&&net.neoforged.fml.ModList.get().isLoaded("proutlost_director"))
            .then(Commands.argument("survey",UuidArgument.uuid()).executes(context->run(context.getSource(),()->{
                var source=context.getSource();var server=source.getServer();
                NativeObservedPhysicalDirectorExport.exportAsync(source.getLevel(),UuidArgument.getUuid(context,"survey")).whenComplete((result,error)->
                    server.execute(()->{if(error!=null)source.sendFailure(Component.literal("Observed geometry export not confirmed: "+error.getClass().getSimpleName()));
                        else source.sendSuccess(()->Component.literal(new com.google.gson.Gson().toJson(result)),false);}));
                source.sendSuccess(()->Component.literal("Observed geometry G0 export queued; NOT_DIRECTOR_IMPORTED"),false);return 1;
            }))));
        root.then(Commands.literal("survey-director-verify").requires(source->source.hasPermission(4)&&net.neoforged.fml.ModList.get().isLoaded("proutlost_director")
                &&Boolean.getBoolean("proutlost.worldprep.observedGeometryQa"))
            .then(Commands.argument("survey",UuidArgument.uuid()).executes(context->run(context.getSource(),()->{
                var source=context.getSource();var server=source.getServer();var player=source.getPlayerOrException();
                NativeObservedGeometryReadback.verifyAsync(source.getLevel(),player,UuidArgument.getUuid(context,"survey")).whenComplete((result,error)->
                    server.execute(()->{if(error!=null)source.sendFailure(Component.literal("Geometry QA readback not confirmed: "+error.getClass().getSimpleName()));
                        else{String json=new com.google.gson.Gson().toJson(result);System.out.println("P008_NATIVE_OBSERVED_GEOMETRY_READBACK "+json);source.sendSuccess(()->Component.literal(json),false);}}));
                return 1;
            }))));
        var pipeline=Commands.literal("pipeline");
        pipeline.then(Commands.literal("start").executes(c->run(c.getSource(),()->NativeQualificationRuntime.start(c.getSource().getLevel()))))
            .then(Commands.literal("pause").executes(c->{NativeQualificationRuntime.pause(c.getSource().getLevel());return 1;}))
            .then(Commands.literal("resume").executes(c->{NativeQualificationRuntime.resume(c.getSource().getLevel());return 1;}))
            .then(Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->Component.literal(NativeQualificationRuntime.status(c.getSource().getLevel())),false);return 1;}));
        root.then(pipeline);
        root.then(Commands.literal("status").executes(c->{c.getSource().sendSuccess(()->Component.literal(NativePhysicalRuntime.status(c.getSource().getLevel())),false);return 1;}));
        root.then(Commands.literal("pause").executes(c->{NativePhysicalRuntime.pause(c.getSource().getLevel());return 1;}));
        for(String kind:List.of("structure","fauna")) {
            var branch=Commands.literal(kind).then(Commands.argument("operation",UuidArgument.uuid())
                .then(Commands.literal("resume").executes(c->run(c.getSource(),()-> {
                    var id=UuidArgument.getUuid(c,"operation");NativePhysicalRuntime.resume(c.getSource().getLevel(),kind,id,false);return id;
                }))));
            if(kind.equals("structure"))branch.then(Commands.argument("operation",UuidArgument.uuid())
                .then(Commands.literal("rollback").executes(c->run(c.getSource(),()-> {
                    var id=UuidArgument.getUuid(c,"operation");NativePhysicalRuntime.resume(c.getSource().getLevel(),kind,id,true);return id;
                }))));
            branch.then(Commands.argument("operation",UuidArgument.uuid())
                .then(Commands.literal("audit-disk").executes(c->run(c.getSource(),()-> {
                    var id=UuidArgument.getUuid(c,"operation");NativePhysicalRuntime.audit(c.getSource().getLevel(),kind,id);return id;
                }))));
            root.then(branch);
        }
        return root;
    }
    @FunctionalInterface private interface Operation {UUID execute()throws Exception;}
    private static int run(CommandSourceStack source,Operation operation) {
        try {var id=operation.execute();source.sendSuccess(()->Component.literal("Physical operation "+id+"; NOT_DISK_VERIFIED; Director NOT_READY"),false);return 1;}
        catch(Exception failure){source.sendFailure(Component.literal("Physical operation refused/paused: "+failure.getMessage()));return 0;}
    }
    private NativePhysicalCommands(){}
}
