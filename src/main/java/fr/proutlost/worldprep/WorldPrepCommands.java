package fr.proutlost.worldprep;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
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
        registerTree(event.getDispatcher());
    }
    /** One production command tree shared with the real parser acceptance tests. */
    public static void registerTree(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var areaArg=Commands.argument("area",worldPrepId());
        dispatcher.register(Commands.literal("proutlost").then(Commands.literal("worldprep").requires(s->s.hasPermission(2))
            .then(Commands.literal("pos1").executes(c->position(c.getSource(),true)))
            .then(Commands.literal("pos2").executes(c->position(c.getSource(),false)))
            .then(Commands.literal("job").then(Commands.argument("id",UuidArgument.uuid())
                .then(Commands.literal("recover").executes(c->{fr.proutlost.worldprep.runtime.BlockMutationRuntime.recoverStandalone(level(c.getSource()),WorldPrepRuntime.data(level(c.getSource())),UuidArgument.getUuid(c,"id"));return 1;}))
                .then(Commands.literal("resume").executes(c->controlJob(c.getSource(),UuidArgument.getUuid(c,"id"),"resume")))
                .then(Commands.literal("pause").executes(c->controlJob(c.getSource(),UuidArgument.getUuid(c,"id"),"pause")))
                .then(Commands.literal("cancel").executes(c->controlJob(c.getSource(),UuidArgument.getUuid(c,"id"),"cancel")))))
            .then(Commands.literal("audit-references").then(Commands.argument("cursor",StringArgumentType.string()).executes(c->auditReferences(c.getSource(),StringArgumentType.getString(c,"cursor")))))
            .then(Commands.literal("area")
                .then(Commands.literal("create").then(Commands.argument("id",worldPrepId()).executes(c->createArea(c.getSource(),rawId(c,"id")))))
                .then(Commands.literal("info").then(Commands.argument("id",worldPrepId()).executes(c->areaInfo(c.getSource(),rawId(c,"id")))))
                .then(Commands.literal("define").then(Commands.argument("id",worldPrepId())
                    .then(Commands.argument("minX",IntegerArgumentType.integer()).then(Commands.argument("minZ",IntegerArgumentType.integer())
                    .then(Commands.argument("maxX",IntegerArgumentType.integer()).then(Commands.argument("maxZ",IntegerArgumentType.integer())
                    .executes(c->defineArea(c.getSource(),rawId(c,"id"),IntegerArgumentType.getInteger(c,"minX"),IntegerArgumentType.getInteger(c,"minZ"),IntegerArgumentType.getInteger(c,"maxX"),IntegerArgumentType.getInteger(c,"maxZ")))))))))
                .then(Commands.literal("mask").then(Commands.argument("id",worldPrepId()).then(Commands.argument("mask",StringArgumentType.word()).suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.Arrays.stream(WorldPrepSavedData.AreaMask.values()).map(Enum::name),b)).executes(c->areaMask(c.getSource(),rawId(c,"id"),StringArgumentType.getString(c,"mask"))))))
                .then(Commands.literal("list").executes(c->listAreas(c.getSource())))
                .then(Commands.literal("delete").then(Commands.argument("id",worldPrepId()).executes(c->deleteArea(c.getSource(),rawId(c,"id"))))))
            .then(Commands.literal("registry")
                .then(Commands.literal("biome").then(Commands.argument("search",StringArgumentType.greedyString()).executes(c->registrySearch(c.getSource(),"biome",StringArgumentType.getString(c,"search")))))
                .then(Commands.literal("block").then(Commands.argument("search",StringArgumentType.greedyString()).executes(c->registrySearch(c.getSource(),"block",StringArgumentType.getString(c,"search")))))
                .then(Commands.literal("entity").then(Commands.argument("search",StringArgumentType.greedyString()).executes(c->registrySearch(c.getSource(),"entity",StringArgumentType.getString(c,"search"))))))
            .then(Commands.literal("analyze").then(areaArg.executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ANALYZE,rawId(c,"area")))))
            .then(Commands.literal("preview")
                .then(Commands.literal("terrain").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.ANALYZE,rawId(c,"area")))))
                .then(Commands.literal("structures").then(Commands.argument("area",worldPrepId()).executes(c->discover(c.getSource(),rawId(c,"area")))))
                .then(Commands.literal("png").then(Commands.argument("pass",StringArgumentType.word())
                    .suggests((context,builder)->{for(var pass:java.util.List.of("biomes","geology","ores","soils","flora","trees","aquatic"))builder.suggest(pass);return builder.buildFuture();})
                    .then(Commands.argument("area",worldPrepId()).executes(c->exportPng(c.getSource(),StringArgumentType.getString(c,"pass"),rawId(c,"area"))))))
                .then(Commands.literal("biomes").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_BIOMES,rawId(c,"area")))))
                .then(Commands.literal("geology").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_GEOLOGY,rawId(c,"area")))))
                .then(Commands.literal("ores").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_ORES,rawId(c,"area"))))))
            .then(Commands.literal("apply").then(fr.proutlost.worldprep.physical.NativePhysicalCommands.structures()).then(fr.proutlost.worldprep.physical.NativePhysicalCommands.fauna()).then(Commands.literal("biomes").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_BIOMES,rawId(c,"area")))))
                .then(Commands.literal("geology").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_GEOLOGY,rawId(c,"area")))))
                .then(Commands.literal("ores").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.APPLY_ORES,rawId(c,"area"))))))
            .then(Commands.literal("rollback").then(Commands.literal("biomes").then(Commands.argument("area",worldPrepId()).then(Commands.argument("snapshot",UuidArgument.uuid()).executes(c->enqueueRollback(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_BIOMES,rawId(c,"area"),UuidArgument.getUuid(c,"snapshot"))))))
                .then(Commands.literal("geology").then(Commands.argument("area",worldPrepId()).then(Commands.argument("snapshot",UuidArgument.uuid()).executes(c->enqueueRollback(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_GEOLOGY,rawId(c,"area"),UuidArgument.getUuid(c,"snapshot"))))))
                .then(Commands.literal("ores").then(Commands.argument("area",worldPrepId()).then(Commands.argument("snapshot",UuidArgument.uuid()).executes(c->enqueueRollback(c.getSource(),WorldPrepSavedData.Operation.ROLLBACK_ORES,rawId(c,"area"),UuidArgument.getUuid(c,"snapshot")))))))
            .then(Commands.literal("batch").then(Commands.argument("area",worldPrepId()).executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.BATCH_ECOLOGY,rawId(c,"area")))))
            .then(Commands.literal("discover").then(Commands.argument("area",worldPrepId()).executes(c->discover(c.getSource(),rawId(c,"area")))))
            .then(fr.proutlost.worldprep.physical.NativePhysicalCommands.control())
            .then(fr.proutlost.worldprep.physical.NativePhysicalCommands.structureModule())
            .then(fr.proutlost.worldprep.physical.NativePhysicalCommands.featureModule())
            .then(fr.proutlost.worldprep.physical.NativePhysicalCommands.faunaModule())
            .then(fr.proutlost.worldprep.physical.NativePhysicalCommands.nativeReconciliation())
            .then(Commands.literal("inspect").executes(c->inspect(c.getSource())))
            .then(Commands.literal("status").executes(c->status(c.getSource())))
            .then(Commands.literal("pause").executes(c->control(c.getSource(),"pause")))
            .then(Commands.literal("resume").executes(c->control(c.getSource(),"resume")))
            .then(Commands.literal("cancel").executes(c->control(c.getSource(),"cancel"))))
            );
        registerEnvironment(dispatcher);
        fr.proutlost.worldprep.location.LocationCommands.register(dispatcher);
    }
    private static void registerEnvironment(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher){
        var preview=Commands.literal("preview");var apply=Commands.literal("apply");var rollback=Commands.literal("rollback");
        for(String pass:java.util.List.of("soils","flora","trees","aquatic")){
            String suffix=pass.toUpperCase(java.util.Locale.ROOT);
            var previewOperation=WorldPrepSavedData.Operation.valueOf("PREVIEW_"+suffix);
            var applyOperation=WorldPrepSavedData.Operation.valueOf("APPLY_"+suffix);
            var rollbackOperation=WorldPrepSavedData.Operation.valueOf("ROLLBACK_"+suffix);
            preview.then(Commands.literal(pass).then(Commands.argument("area",worldPrepId())
                .executes(c->enqueue(c.getSource(),previewOperation,rawId(c,"area")))));
            apply.then(Commands.literal(pass).then(Commands.argument("area",worldPrepId())
                .executes(c->enqueue(c.getSource(),applyOperation,rawId(c,"area")))));
            rollback.then(Commands.literal(pass).then(Commands.argument("area",worldPrepId())
                .then(Commands.argument("snapshot",UuidArgument.uuid()).executes(c->enqueueRollback(c.getSource(),rollbackOperation,rawId(c,"area"),UuidArgument.getUuid(c,"snapshot"))))));
        }
        preview.then(Commands.literal("habitats").then(Commands.argument("area",worldPrepId())
            .executes(c->enqueue(c.getSource(),WorldPrepSavedData.Operation.PREVIEW_HABITATS,rawId(c,"area")))));
        var trees=Commands.literal("trees").then(Commands.argument("id",worldPrepId())
            .then(Commands.argument("mode",StringArgumentType.word())
                .suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.Arrays.stream(fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.values()).map(Enum::name),b))
                .executes(c->areaTrees(c.getSource(),rawId(c,"id"),StringArgumentType.getString(c,"mode")))));
        var override=Commands.literal("override")
            .then(Commands.literal("biome").then(Commands.argument("biome",net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .executes(c->override(c.getSource(),"biome",rawId(c,"biome")))))
            .then(Commands.literal("palette").then(Commands.argument("palette",StringArgumentType.word())
                .suggests((c,b)->net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("coastal","lowland_forest","open_lowland","marine_deep","marine"),b))
                .executes(c->override(c.getSource(),"palette",StringArgumentType.getString(c,"palette")))))
            .then(Commands.literal("clear").executes(c->override(c.getSource(),"clear","")));
        dispatcher.register(Commands.literal("proutlost").then(Commands.literal("worldprep").requires(s->s.hasPermission(2))
            .then(preview).then(apply).then(rollback).then(Commands.literal("area").then(trees)).then(override)));
    }
    private static int areaTrees(CommandSourceStack source,String id,String requested){
        if(!source.hasPermission(2))throw new IllegalStateException("OP2 required");
        final fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode mode;
        try{mode=fr.proutlost.worldprep.ecology.VegetationPlanner.TreeMode.valueOf(requested.toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException invalid){source.sendFailure(Component.literal("Unknown tree mode; use NO_TREES, SPARSE, NORMAL, DENSE or MANUAL_ONLY"));return 0;}
        var data=WorldPrepRuntime.data(level(source));var area=WorldPrepRuntime.requireArea(data,id);WorldPrepRuntime.assertUnlocked(data,area);
        data.areas().put(id,area.withTreeMode(mode));data.changed();
        source.sendSuccess(()->Component.literal("Area "+id+" trees="+mode+"; existing previews become stale"),false);return 1;
    }
    private static int override(CommandSourceStack source,String kind,String requested)throws CommandSyntaxException{
        if(!source.hasPermission(2))throw new IllegalStateException("OP2 required");
        var player=source.getPlayerOrException();var data=WorldPrepRuntime.data(player.serverLevel());var selection=data.selections().get(player.getUUID());
        if(selection==null||selection.x1()==null||selection.z1()==null||selection.x2()==null||selection.z2()==null)
            throw new IllegalStateException("Set pos1 and pos2 first");
        var area=new WorldPrepSavedData.Area("proutlost:override_selection",player.serverLevel().dimension().location().toString(),
            Math.min(selection.x1(),selection.x2()),Math.min(selection.z1(),selection.z2()),Math.max(selection.x1(),selection.x2()),Math.max(selection.z1(),selection.z2()));
        WorldPrepRuntime.assertUnlocked(data,area);var bounds=fr.proutlost.worldprep.plan.BiomeBounds.inside(area);
        if(bounds.cells()>4096)throw new IllegalArgumentException("Override selection exceeds 4096 complete quart cells");
        String exact=null,palette=null;
        if(kind.equals("biome")){
            var id=ResourceLocation.tryParse(requested);
            if(id==null||!player.serverLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).keySet().contains(id))
                throw new IllegalArgumentException("Unknown runtime biome "+requested);
            exact=id.toString();
        }else if(kind.equals("palette")){
            palette=requested.toLowerCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("coastal","lowland_forest","open_lowland","marine_deep","marine").contains(palette))
                throw new IllegalArgumentException("Unknown palette override "+requested);
        }else if(!kind.equals("clear"))throw new IllegalArgumentException("Unknown override operation");
        if(!kind.equals("clear")){
            long added=0;for(int z=bounds.minQuartZ();z<=bounds.maxQuartZ();z++)for(int x=bounds.minQuartX();x<=bounds.maxQuartX();x++)
                if(!data.overrides().containsKey(WorldPrepSavedData.cellKey(x,z)))added++;
            if((long)data.overrides().size()+added>65536)throw new IllegalStateException("Override metadata capacity 65536 reached");
        }
        for(int z=bounds.minQuartZ();z<=bounds.maxQuartZ();z++)for(int x=bounds.minQuartX();x<=bounds.maxQuartX();x++){
            long key=WorldPrepSavedData.cellKey(x,z);
            if(kind.equals("clear"))data.overrides().remove(key);else data.overrides().put(key,new WorldPrepSavedData.OverrideCell(x,z,exact,palette));
        }
        data.changed();source.sendSuccess(()->Component.literal("Override "+kind+": "+bounds.cells()+" complete quart cells; metadata only, preview again before apply"),false);return 1;
    }
    /** Registered network-safe argument shared by client and server command trees. */
    private static WorldPrepIdArgument worldPrepId() {
        return new WorldPrepIdArgument();
    }
    private static String rawId(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,String name){
        Object parsed=context.getArgument(name,Object.class);
        String id=parsed instanceof ResourceLocation resource?resource.toString():String.valueOf(parsed);
        if(ResourceLocation.tryParse(id)==null)throw new IllegalArgumentException("Invalid resource argument "+name);
        return id;
    }
    private static int areaMask(CommandSourceStack source,String id,String requested){
        final WorldPrepSavedData.AreaMask mask;try{mask=WorldPrepSavedData.AreaMask.valueOf(requested.toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException invalid){source.sendFailure(Component.literal("Unknown area mask; CONNECTED_ISLAND is not implemented"));return 0;}
        var data=WorldPrepRuntime.data(level(source));var area=WorldPrepRuntime.requireArea(data,id);WorldPrepRuntime.assertUnlocked(data,area);
        data.areas().put(id,area.withMask(mask));data.changed();source.sendSuccess(()->Component.literal("Area "+id+" mask="+mask+"; coast is the <=4-block water/land boundary, existing previews become stale"),false);return 1;
    }
    private static int position(CommandSourceStack s,boolean first)throws CommandSyntaxException{ServerPlayer p=s.getPlayerOrException();ServerLevel l=p.serverLevel();var d=WorldPrepRuntime.data(l);var old=d.selections().getOrDefault(p.getUUID(),new WorldPrepSavedData.Selection(null,null,null,null));var pos=p.blockPosition();d.selections().put(p.getUUID(),first?new WorldPrepSavedData.Selection(pos.getX(),pos.getZ(),old.x2(),old.z2()):new WorldPrepSavedData.Selection(old.x1(),old.z1(),pos.getX(),pos.getZ()));d.changed();s.sendSuccess(()->Component.literal((first?"pos1":"pos2")+" = "+pos.getX()+", "+pos.getZ()),false);return Command.SINGLE_SUCCESS;}
    private static int createArea(CommandSourceStack s,String id)throws CommandSyntaxException{if(ResourceLocation.tryParse(id)==null)throw new IllegalArgumentException("Area ID must be namespaced");ServerPlayer p=s.getPlayerOrException();var d=WorldPrepRuntime.data(p.serverLevel());var sel=d.selections().get(p.getUUID());if(sel==null||sel.x1()==null||sel.x2()==null)throw new IllegalStateException("Set pos1 and pos2 first");var a=new WorldPrepSavedData.Area(id,p.serverLevel().dimension().location().toString(),Math.min(sel.x1(),sel.x2()),Math.min(sel.z1(),sel.z2()),Math.max(sel.x1(),sel.x2()),Math.max(sel.z1(),sel.z2()));if(d.areas().putIfAbsent(id,a)!=null)throw new IllegalStateException("Area already exists");d.changed();s.sendSuccess(()->Component.literal("Created "+id),false);return Command.SINGLE_SUCCESS;}
    private static int areaInfo(CommandSourceStack s,String id){var a=WorldPrepRuntime.requireArea(WorldPrepRuntime.data(level(s)),id);s.sendSuccess(()->Component.literal(a.id()+" ["+a.minX()+","+a.minZ()+" -> "+a.maxX()+","+a.maxZ()+"] "+a.dimension()+" mask="+a.mask()+" trees="+a.treeMode()),false);return 1;}
    private static int listAreas(CommandSourceStack s){var d=WorldPrepRuntime.data(level(s));d.areas().values().forEach(a->s.sendSuccess(()->Component.literal(a.id()),false));return d.areas().size();}
    private static int deleteArea(CommandSourceStack s,String id){var d=WorldPrepRuntime.data(level(s));if(d.areas().remove(id)==null)throw new IllegalArgumentException("Unknown area");d.changed();return 1;}
    private static int exportPng(CommandSourceStack source,String pass,String area){
        try{
            var server=source.getServer();var export=fr.proutlost.worldprep.runtime.WorldPrepPreviewExports.export(level(source),pass,area);
            export.whenCompleteAsync((report,error)->{
                if(!server.isRunning())return;
                if(source.getEntity() instanceof ServerPlayer player){if(server.getPlayerList().getPlayer(player.getUUID())!=player||!player.hasPermissions(2))return;}
                else if(!source.hasPermission(2))return;
                if(error!=null)source.sendFailure(Component.literal("PNG export failed or cancelled; sealed plan and world remain unchanged."));
                else source.sendSuccess(()->Component.literal("PNG preview: "+report.image()+"; legend: "+report.legend()+"; metadata: "+report.metadata()+(report.cached()?" (verified existing export)":"")),false);
            },server);
            source.sendSuccess(()->Component.literal("PNG export queued from the sealed preview; no world scan or mutation."),false);return 1;
        }catch(RuntimeException unavailable){source.sendFailure(Component.literal("PNG export refused: "+unavailable.getMessage()));return 0;}
    }
    private static int enqueue(CommandSourceStack s,WorldPrepSavedData.Operation op,String area){var id=WorldPrepRuntime.enqueue(level(s),op,area);s.sendSuccess(()->Component.literal("Queued "+op+" job "+id),false);return 1;}
    private static int enqueueRollback(CommandSourceStack s,WorldPrepSavedData.Operation op,String area,java.util.UUID snapshot){var id=WorldPrepRuntime.enqueueRollback(level(s),op,area,snapshot);s.sendSuccess(()->Component.literal("Queued "+op+" job "+id+" for exact snapshot "+snapshot),false);return 1;}
    private static int registrySearch(CommandSourceStack source,String kind,String search){
        net.minecraft.core.Registry<?> registry=switch(kind){
            case "biome"->level(source).registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            case "block"->level(source).registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            case "entity"->level(source).registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENTITY_TYPE);
            default->throw new IllegalArgumentException("Unsupported runtime registry "+kind);
        };
        if(search.isBlank()||search.length()>256){source.sendFailure(Component.literal("Registry search must contain 1..256 characters"));return 0;}String needle=search.strip().toLowerCase(java.util.Locale.ROOT);var shown=new java.util.TreeSet<String>();int matches=0;
        for(var key:registry.keySet()){String id=key.toString();if(!id.toLowerCase(java.util.Locale.ROOT).contains(needle))continue;matches++;shown.add(id);if(shown.size()>32)shown.pollLast();}
        if(matches==0){source.sendFailure(Component.literal("No matching runtime "+kind+" registry IDs for "+search));return 0;}
        String report="Runtime registry "+kind+" matches="+matches+" shown="+shown.size()+" remaining="+(matches-shown.size())+": "+String.join(", ",shown);
        source.sendSuccess(()->Component.literal(report),false);return matches;
    }
    private static int inspect(CommandSourceStack s)throws CommandSyntaxException{var p=s.getPlayerOrException();var pos=p.blockPosition();var sample=MinecraftTerrainSampler.sample(p.serverLevel(),pos.getX(),pos.getZ());String current=p.serverLevel().getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse("unregistered");s.sendSuccess(()->Component.literal("Surface="+sample.surfaceY()+" waterDepth="+sample.waterDepth()+" slope="+sample.slope()+" roughness="+sample.roughness()+" coast="+sample.coastDistance()+" biome="+current),false);var inland=MinecraftTerrainSampler.inlandWater(p.serverLevel(),pos.getX(),pos.getZ());double noise=new net.minecraft.world.level.levelgen.synth.SimplexNoise(net.minecraft.util.RandomSource.create(fr.proutlost.worldprep.plan.PlanIdentity.seed(p.serverLevel()))).getValue(pos.getX()/256.0,pos.getZ()/256.0);s.sendSuccess(()->Component.literal("Local terrain inlandObservedDistance="+(inland.observedDistance()<0?"UNKNOWN":inland.observedDistance())+" openBoundaryUnresolved="+inland.unresolvedBoundary()+" noWaterObservedWithin4="+inland.noWater()+" waterSampleSpacing=1 distanceMetric=Chebyshev noiseScaleBlocks=256 noise="+noise+"; finite-window observation, not a global nearest-water claim"),false);var blocks=BlockMutationRuntime.data(p.serverLevel());blocks.plans().values().forEach(plan->s.sendSuccess(()->Component.literal(plan.pass+" area="+plan.area+" profile="+plan.profile+" eligible="+plan.eligible()+" skipped="+plan.skipped()+" replacements="+plan.entryCount()+" fingerprint="+plan.fingerprint()),false));return 1;}
    private static int status(CommandSourceStack s){var d=WorldPrepRuntime.data(level(s));if(d.jobs().isEmpty())s.sendSuccess(()->Component.literal("No WorldPrep jobs"),false);d.jobs().values().forEach(j->s.sendSuccess(()->Component.literal(j.id()+" "+j.operation()+" "+j.state()+" functional="+j.functionalState()+" area="+j.area()+" cursor="+j.cursor()+" snapshot/child="+j.snapshotId()+(j.error().isEmpty()?"":" error="+j.error())),false));d.analyses().forEach((id,a)->analysisStatus(s,id,a));return d.jobs().size();}
    private static void analysisStatus(CommandSourceStack source,java.util.UUID id,WorldPrepSavedData.Analysis a){
        if(a.samples==0){source.sendSuccess(()->Component.literal("Analysis "+id+": 0/"+a.totalCells+" terrain samples; no world or preview mutation"),false);return;}
        long columns=((long)a.input.maxX()-a.input.minX()+1)*((long)a.input.maxZ()-a.input.minZ()+1),chunks=((long)(a.input.maxX()>>4)-(a.input.minX()>>4)+1)*((long)(a.input.maxZ()>>4)-(a.input.minZ()>>4)+1);
        double n=a.samples,normalized=Math.max(0,Math.min(1,(a.surfaceSum/n-a.minBuildHeight)/(a.maxBuildHeight-a.minBuildHeight)));
        source.sendSuccess(()->Component.literal("Analysis "+id+" "+(a.complete?"COMPLETE":"PARTIAL")+" cells="+a.samples+"/"+a.totalCells+" sampleSpacing=4 blocks="+columns+" chunks="+chunks+" y="+a.minSurface+".."+a.maxSurface+" meanY="+(a.surfaceSum/n)+" normalizedAltitude="+normalized+" slopeMean="+(a.slopeSum/n)+" roughnessMean="+(a.roughnessSum/n)+" waterSamples="+a.waterSamples+"/"+a.samples+" waterDepthMean="+(a.waterDepthSum/n)+" coastMeanCapped32="+(a.coastSum/n)+" protectedSamples="+a.protectedSamples+" directProtectedZones="+a.protectedZones+" biomeRegistry="+a.registryEntries+" digest="+a.terrainDigest),false);
        source.sendSuccess(()->Component.literal("Analysis "+id+" localTerrainMeasures="+a.terrainSamples+"/"+a.samples+" inlandObservedDistanceMean="+(a.inlandDistanceSamples==0?"UNKNOWN":Double.toString(a.inlandDistanceSum/a.inlandDistanceSamples))+" inlandObservedSamples="+a.inlandDistanceSamples+" inlandOpenBoundaryUnresolved="+a.inlandUnresolvedSamples+" noWaterObservedWithin4="+a.inlandNoWaterSamples+" noiseScaleBlocks=256 noiseMean="+(a.terrainSamples==0?"UNKNOWN":Double.toString(a.noiseSum/a.terrainSamples))+" noiseMin="+a.noiseMin+" noiseMax="+a.noiseMax+"; inland distances use a block-complete surface-water grid and Chebyshev distance within4, not global minima; open water is UNKNOWN unless above configured marine surface."),false);
        source.sendSuccess(()->Component.literal("Analysis "+id+" altitudeBands16="+analysisCounts(a.altitudeBands)+" slopeBands="+analysisCounts(a.slopeBands)+" suggestedBiomes="+analysisCounts(a.biomes)+" missingBiomes="+analysisCounts(a.missingBiomes)+"; full counts persist in WorldPrep SavedData. Not measured: host volumes, vegetation suitability, habitat estimates. Inland-water distance is finite-window/censored; low-frequency noise is profile-seeded vanilla Simplex at 1/256 blocks."),false);
    }
    private static String analysisCounts(java.util.Map<String,Long> counts){return counts.entrySet().stream().limit(16).map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining(","))+(counts.size()>16?",...":"");}
    private static int control(CommandSourceStack source,String action){
        var data=WorldPrepRuntime.data(level(source));
        var job=data.jobs().values().stream().filter(j->j.state()==WorldPrepSavedData.JobState.RUNNING||j.state()==WorldPrepSavedData.JobState.QUEUED||j.state()==WorldPrepSavedData.JobState.PAUSED)
                .filter(j->data.jobs().values().stream().noneMatch(p->p.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY&&j.id().equals(p.snapshotId())))
                .findFirst().orElseThrow(()->new IllegalStateException("No controllable job"));
        return controlJob(source,job.id(),action);
    }
    private static ServerLevel level(CommandSourceStack s){return s.getLevel();}
    private static int defineArea(CommandSourceStack source,String id,int minX,int minZ,int maxX,int maxZ) {
        if(ResourceLocation.tryParse(id)==null)throw new IllegalArgumentException("Invalid area ID");
        new fr.proutlost.worldprep.ecology.EcologyTiles(minX,minZ,maxX,maxZ);
        var data=WorldPrepRuntime.data(level(source));
        var area=new WorldPrepSavedData.Area(id,level(source).dimension().location().toString(),minX,minZ,maxX,maxZ);
        if(data.areas().putIfAbsent(id,area)!=null)throw new IllegalStateException("Area already exists");
        data.changed();return 1;
    }

    private static int auditReferences(CommandSourceStack source,String cursor) {
        var data=WorldPrepRuntime.data(level(source));
        var blocks=BlockMutationRuntime.data(level(source));
        if(!blocks.compatible())throw new IllegalStateException("Legacy block metadata incompatible; retain original files for offline recovery");
        var sources=java.util.List.of(
            fr.proutlost.worldprep.recovery.ReferenceTraversal.source(data.plans(),s->s,s->s,
                e->e.getValue().planId==null?null:new fr.proutlost.worldprep.recovery.ReferenceInventory.Reference("plans",e.getValue().planId)),
            fr.proutlost.worldprep.recovery.ReferenceTraversal.source(blocks.plans(),s->s,s->s,
                e->e.getValue().planId()==null?null:new fr.proutlost.worldprep.recovery.ReferenceInventory.Reference("plans",e.getValue().planId())),
            fr.proutlost.worldprep.recovery.ReferenceTraversal.source(data.snapshots(),java.util.UUID::fromString,java.util.UUID::toString,
                e->new fr.proutlost.worldprep.recovery.ReferenceInventory.Reference("biome-journals",e.getKey())),
            fr.proutlost.worldprep.recovery.ReferenceTraversal.source(blocks.journals(),java.util.UUID::fromString,java.util.UUID::toString,
                e->new fr.proutlost.worldprep.recovery.ReferenceInventory.Reference("journals",e.getKey())));
        var page=fr.proutlost.worldprep.recovery.ReferenceTraversal.page(sources,cursor);
        var root=level(source).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("worldprep-v2");
        fr.proutlost.worldprep.recovery.ReferenceInventory.inspect(root,page.references()).forEach(f->source.sendSuccess(()->Component.literal(f.toString()),false));
        source.sendSuccess(()->Component.literal("Read-only live audit: examined="+page.examined()+" next cursor="+page.next()
            +"; restart at start after metadata changes; missing history is never reconstructed"),false);
        return page.references().size();
    }

    private static int controlJob(CommandSourceStack source,java.util.UUID id,String action) {
        var data=WorldPrepRuntime.data(level(source));var job=data.jobs().get(id);
        if(job==null)throw new IllegalArgumentException("Unknown job");
        if(data.jobs().values().stream().anyMatch(p->p.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY&&id.equals(p.snapshotId())))
            throw new IllegalStateException("Child owned by batch; control the parent UUID");
        var state=switch(action){
            case "resume"->{if(job.state()!=WorldPrepSavedData.JobState.PAUSED)throw new IllegalStateException("Job is not paused");yield WorldPrepSavedData.JobState.RUNNING;}
            case "pause"->{if(job.state()!=WorldPrepSavedData.JobState.RUNNING&&job.state()!=WorldPrepSavedData.JobState.QUEUED)throw new IllegalStateException("Job is not running");yield WorldPrepSavedData.JobState.PAUSED;}
            default->{if(job.state()==WorldPrepSavedData.JobState.COMPLETED)throw new IllegalStateException("Completed job requires explicit rollback");yield WorldPrepSavedData.JobState.CANCELLED;}
        };
        if(state==WorldPrepSavedData.JobState.CANCELLED&&job.operation()==WorldPrepSavedData.Operation.BATCH_ECOLOGY&&job.snapshotId()!=null){
            var child=data.jobs().get(job.snapshotId());if(child!=null&&child.state()!=WorldPrepSavedData.JobState.COMPLETED)data.jobs().put(child.id(),child.withState(WorldPrepSavedData.JobState.CANCELLED));
        }
        data.jobs().put(id,job.withState(state));data.changed();level(source).getDataStorage().save();return 1;
    }

    private static int discover(CommandSourceStack source,String area) {
        if (!WorldPrepConfig.ENABLED.get()) throw new IllegalStateException("WorldPrep is disabled by configuration");
        try {
            var path=fr.proutlost.worldprep.runtime.DiscoveryExporter.export(level(source),area);
            source.sendSuccess(()->Component.literal("DISCOVERY_ONLY, Director NOT READY: "+path),false);
            return 1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discovery interrupted; retry the same tile",exception);
        } catch (java.io.IOException exception) { throw new IllegalStateException("Discovery export failed",exception); }
    }

}
