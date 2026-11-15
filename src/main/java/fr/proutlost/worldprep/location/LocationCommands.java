package fr.proutlost.worldprep.location;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.*;
import fr.proutlost.worldprep.area.WorldPrepArea;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.runtime.WorldPrepRuntime;
import fr.proutlost.worldprep.validation.ValidationReport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

/** OP2 authoring handlers over the existing location models; no automatic sites. */
public final class LocationCommands {
    private LocationCommands(){}
    private static final Map<MinecraftServer,Map<UUID,State>> STATES=new WeakHashMap<>();
    private static final Set<MinecraftServer> EXPORTING=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listenerInstalled;
    private static final class State {String dimension,active;Integer x1,z1,x2,z2;boolean debug,exporting;}
    @FunctionalInterface private interface Action {int run()throws CommandSyntaxException;}
    private static int execute(CommandSourceStack source,Action action)throws CommandSyntaxException{
        if(!source.hasPermission(2))throw new SimpleCommandExceptionType(Component.literal("OP2 required")).create();
        try{return action.run();}catch(IllegalArgumentException|IllegalStateException e){throw new SimpleCommandExceptionType(Component.literal(e.getMessage()==null?"Location operation refused":e.getMessage())).create();}
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("location").requires(s->s.hasPermission(2))
            .then(Commands.literal("pos1").executes(c->execute(c.getSource(),()->position(c.getSource(),true))))
            .then(Commands.literal("pos2").executes(c->execute(c.getSource(),()->position(c.getSource(),false))))
            .then(Commands.literal("create").then(Commands.argument("id",ResourceLocationArgument.id())
                .executes(c->execute(c.getSource(),()->create(c.getSource(),ResourceLocationArgument.getId(c,"id").toString(),LocationDefinition.Type.NARRATIVE_STATIC)))
                .then(Commands.argument("type",StringArgumentType.word()).suggests((c,b)->SharedSuggestionProvider.suggest(Arrays.stream(LocationDefinition.Type.values()).map(Enum::name),b))
                    .executes(c->execute(c.getSource(),()->create(c.getSource(),ResourceLocationArgument.getId(c,"id").toString(),LocationDefinition.Type.valueOf(StringArgumentType.getString(c,"type").toUpperCase(Locale.ROOT))))))))
            .then(Commands.literal("info").then(Commands.argument("id",ResourceLocationArgument.id()).executes(c->execute(c.getSource(),()->info(c.getSource(),ResourceLocationArgument.getId(c,"id").toString())))))
            .then(Commands.literal("list").executes(c->execute(c.getSource(),()->list(c.getSource()))))
            .then(Commands.literal("delete").then(Commands.argument("id",ResourceLocationArgument.id()).executes(c->execute(c.getSource(),()->delete(c.getSource(),ResourceLocationArgument.getId(c,"id").toString())))))
            .then(Commands.literal("anchor")
                .then(Commands.literal("add").then(Commands.argument("name",StringArgumentType.word()).executes(c->execute(c.getSource(),()->anchor(c.getSource(),StringArgumentType.getString(c,"name"),true)))))
                .then(Commands.literal("remove").then(Commands.argument("name",StringArgumentType.word()).executes(c->execute(c.getSource(),()->anchor(c.getSource(),StringArgumentType.getString(c,"name"),false))))))
            .then(Commands.literal("zone")
                .then(Commands.literal("add").then(Commands.argument("name",StringArgumentType.word()).then(Commands.argument("type",StringArgumentType.word())
                    .suggests((c,b)->SharedSuggestionProvider.suggest(Arrays.stream(LocationZone.Kind.values()).map(Enum::name),b))
                    .executes(c->execute(c.getSource(),()->zone(c.getSource(),StringArgumentType.getString(c,"name"),LocationZone.Kind.valueOf(StringArgumentType.getString(c,"type").toUpperCase(Locale.ROOT))))))))
                .then(Commands.literal("remove").then(Commands.argument("name",StringArgumentType.word()).executes(c->execute(c.getSource(),()->removeZone(c.getSource(),StringArgumentType.getString(c,"name")))))))
            .then(Commands.literal("protect").executes(c->execute(c.getSource(),()->protect(c.getSource(),true))))
            .then(Commands.literal("unprotect").executes(c->execute(c.getSource(),()->protect(c.getSource(),false))))
            .then(Commands.literal("debug")
                .then(Commands.literal("on").executes(c->execute(c.getSource(),()->debug(c.getSource(),true))))
                .then(Commands.literal("off").executes(c->execute(c.getSource(),()->debug(c.getSource(),false)))))
            .then(Commands.literal("validate").executes(c->execute(c.getSource(),()->validate(c.getSource()))))
            .then(Commands.literal("export").executes(c->execute(c.getSource(),()->export(c.getSource(),Optional.empty())))
                .then(Commands.argument("world_id",UuidArgument.uuid()).executes(c->execute(c.getSource(),()->export(c.getSource(),Optional.of(UuidArgument.getUuid(c,"world_id")))))));
        dispatcher.register(Commands.literal("proutlost").then(root));
        dispatcher.register(Commands.literal("proutlost").then(Commands.literal("worldprep").requires(s->s.hasPermission(2))
            .then(Commands.literal("protect")
                .then(Commands.literal("add").then(Commands.argument("name",StringArgumentType.word()).executes(c->execute(c.getSource(),()->genericProtectAdd(c.getSource(),StringArgumentType.getString(c,"name"))))))
                .then(Commands.literal("remove").then(Commands.argument("name",StringArgumentType.word()).executes(c->execute(c.getSource(),()->genericProtectRemove(c.getSource(),StringArgumentType.getString(c,"name"))))))
                .then(Commands.literal("list").executes(c->execute(c.getSource(),()->genericProtectList(c.getSource())))))));
        if(!listenerInstalled){NeoForge.EVENT_BUS.addListener(LocationCommands::tick);listenerInstalled=true;}
    }
    private static LocationRegistry registry(CommandSourceStack source){return WorldPrepRuntime.data(source.getLevel()).locations();}
    private static State state(ServerPlayer player){return STATES.computeIfAbsent(player.getServer(),s->new HashMap<>()).computeIfAbsent(player.getUUID(),id->new State());}
    private static void say(CommandSourceStack source,String text){source.sendSuccess(()->Component.literal(text),false);}
    private static String active(CommandSourceStack source)throws CommandSyntaxException{var s=state(source.getPlayerOrException());if(s.active==null)throw new IllegalStateException("Create a location or select it with location info <id>");registry(source).require(s.active);return s.active;}
    private static WorldPrepArea selection(CommandSourceStack source,String id)throws CommandSyntaxException{
        var player=source.getPlayerOrException();var s=state(player);var dimension=source.getLevel().dimension().location().toString();
        if(!dimension.equals(s.dimension)||s.x1==null||s.z1==null||s.x2==null||s.z2==null)throw new IllegalStateException("Set location pos1 and pos2 in this dimension first");
        return WorldPrepArea.between(id,dimension,s.x1,s.z1,s.x2,s.z2);
    }
    private static int position(CommandSourceStack source,boolean first)throws CommandSyntaxException{
        var p=source.getPlayerOrException();var state=state(p);var dim=p.serverLevel().dimension().location().toString();var pos=p.blockPosition();
        if(first){if(!dim.equals(state.dimension)){state.x2=null;state.z2=null;}state.dimension=dim;state.x1=pos.getX();state.z1=pos.getZ();}
        else{if(!dim.equals(state.dimension)||state.x1==null)throw new IllegalStateException("Set location pos1 in this dimension first");state.x2=pos.getX();state.z2=pos.getZ();}
        say(source,"Location "+(first?"pos1":"pos2")+"="+pos.getX()+","+pos.getZ()+" "+dim);return 1;
    }
    private static int create(CommandSourceStack source,String id,LocationDefinition.Type type)throws CommandSyntaxException{
        var bounds=selection(source,id);var border=source.getLevel().getWorldBorder();
        if(bounds.minX()<border.getMinX()||bounds.maxX()>border.getMaxX()||bounds.minZ()<border.getMinZ()||bounds.maxZ()>border.getMaxZ())throw new IllegalArgumentException("Location selection outside world border");
        for(var other:source.getServer().getAllLevels())if(other!=source.getLevel()&&WorldPrepRuntime.data(other).locations().locations().containsKey(id))throw new IllegalArgumentException("Location ID already authored in another dimension");
        var data=WorldPrepRuntime.data(source.getLevel());data.locations().create(new LocationDefinition(id,type,bounds,Map.of()));data.changed();state(source.getPlayerOrException()).active=id;say(source,"Created location "+id+" type="+type+"; WorldPrep preservation applies until an explicit transform authorization");return 1;
    }
    private static int info(CommandSourceStack source,String id){var r=registry(source);var l=r.require(id);if(source.getEntity() instanceof ServerPlayer p)state(p).active=id;
        say(source,l.id()+" type="+l.type()+" dimension="+l.bounds().dimension()+" bounds="+l.bounds().minX()+","+l.bounds().minZ()+".."+l.bounds().maxX()+","+l.bounds().maxZ()+" anchors="+l.anchors().keySet()+" zones="+r.zones(id).keySet()+" transformAllowed="+r.transformationAllowed(id)+" revision="+r.revision());return 1;}
    private static int list(CommandSourceStack source){var r=registry(source);if(r.locations().isEmpty())say(source,"No authored locations");r.locations().values().forEach(l->say(source,l.id()+" "+l.type()));return r.locations().size();}
    private static int delete(CommandSourceStack source,String id){var data=WorldPrepRuntime.data(source.getLevel());data.locations().delete(id);data.changed();var states=STATES.get(source.getServer());if(states!=null)states.values().stream().filter(s->id.equals(s.active)).forEach(s->{s.active=null;s.debug=false;});say(source,"Deleted location "+id);return 1;}
    private static int anchor(CommandSourceStack source,String name,boolean add)throws CommandSyntaxException{
        var id=active(source);var data=WorldPrepRuntime.data(source.getLevel());if(add){var p=source.getPlayerOrException();var pos=p.blockPosition();if(pos.getY()<source.getLevel().getMinBuildHeight()||pos.getY()>=source.getLevel().getMaxBuildHeight())throw new IllegalArgumentException("Anchor outside build height");data.locations().anchor(id,name,new LocationDefinition.Anchor(p.serverLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ()));}else data.locations().removeAnchor(id,name);
        data.changed();say(source,(add?"Recorded ":"Removed ")+id+" anchor "+name);return 1;
    }
    private static int zone(CommandSourceStack source,String name,LocationZone.Kind kind)throws CommandSyntaxException{var id=active(source);var data=WorldPrepRuntime.data(source.getLevel());data.locations().zone(id,name,selection(source,id+"/zone/"+name),kind);data.changed();say(source,"Recorded "+id+" zone "+name+" type="+kind);return 1;}
    private static int removeZone(CommandSourceStack source,String name)throws CommandSyntaxException{var data=WorldPrepRuntime.data(source.getLevel());var id=active(source);data.locations().removeZone(id,name);data.changed();say(source,"Removed "+id+" zone "+name);return 1;}
    private static int protect(CommandSourceStack source,boolean protect)throws CommandSyntaxException{var data=WorldPrepRuntime.data(source.getLevel());var id=active(source);data.locations().protect(id,protect);data.changed();say(source,id+(protect?" protected":" explicitly transformable; protected/manual subzones remain preserved"));return 1;}
    private static int genericProtectAdd(CommandSourceStack source,String name)throws CommandSyntaxException{
        LocationRegistry.name(name);var player=source.getPlayerOrException();var data=WorldPrepRuntime.data(source.getLevel());var selected=data.selections().get(player.getUUID());
        if(selected==null||selected.x1()==null||selected.z1()==null||selected.x2()==null||selected.z2()==null)throw new IllegalStateException("Set worldprep pos1 and pos2 in this dimension first");
        if(data.protections().containsKey(name))throw new IllegalArgumentException("Protected zone already exists");if(data.protections().size()>=256)throw new IllegalStateException("Generic protected-zone authoring capacity reached");
        var zone=new fr.proutlost.worldprep.persistence.WorldPrepSavedData.ProtectedZone(name,source.getLevel().dimension().location().toString(),Math.min(selected.x1(),selected.x2()),Math.min(selected.z1(),selected.z2()),Math.max(selected.x1(),selected.x2()),Math.max(selected.z1(),selected.z2()));
        long overlaps=data.protections().values().stream().filter(p->p.dimension().equals(zone.dimension())&&p.minX()<=zone.maxX()&&p.maxX()>=zone.minX()&&p.minZ()<=zone.maxZ()&&p.maxZ()>=zone.minZ()).count();
        data.protections().put(name,zone);data.changed();say(source,"Protected zone "+name+" recorded; overlaps="+overlaps+" (preservation union)");return 1;
    }
    private static int genericProtectRemove(CommandSourceStack source,String name){LocationRegistry.name(name);var data=WorldPrepRuntime.data(source.getLevel());if(data.protections().remove(name)==null)throw new IllegalArgumentException("Unknown protected zone "+name);data.changed();say(source,"Removed protected zone "+name);return 1;}
    private static int genericProtectList(CommandSourceStack source){var zones=WorldPrepRuntime.data(source.getLevel()).protections();if(zones.isEmpty())say(source,"No generic protected zones");new TreeMap<>(zones).forEach((id,p)->say(source,id+" "+p.dimension()+" bounds="+p.minX()+","+p.minZ()+".."+p.maxX()+","+p.maxZ()));return zones.size();}
    private static int validate(CommandSourceStack source){var r=registry(source);var level=source.getLevel();var report=r.validate(level.dimension().location().toString(),level.getMinBuildHeight(),level.getMaxBuildHeight());
        say(source,"Location validation: locations="+r.locations().size()+" revision="+r.revision()+" findings="+report.counts());report.findings().stream().limit(64).forEach(f->say(source,f.severity()+" "+f.code()+" "+f.message()));
        return report.findings().stream().anyMatch(f->f.severity()==ValidationReport.Severity.ERROR||f.severity()==ValidationReport.Severity.FATAL)?0:1;
    }
    private static int debug(CommandSourceStack source,boolean enabled)throws CommandSyntaxException{var p=source.getPlayerOrException();if(enabled)active(source);state(p).debug=enabled;say(source,"Private location visualization "+(enabled?"on":"off"));return 1;}
    private static void tick(ServerTickEvent.Post event){
        var server=event.getServer();var states=STATES.get(server);if(states==null)return;states.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);if(server.getTickCount()%20!=0)return;
        for(var e:states.entrySet())if(e.getValue().debug){var p=server.getPlayerList().getPlayer(e.getKey());var s=e.getValue();if(p==null||!p.hasPermissions(2)){s.debug=false;continue;}
            var r=WorldPrepRuntime.data(p.serverLevel()).locations();var l=r.locations().get(s.active);if(l==null||!l.bounds().dimension().equals(p.serverLevel().dimension().location().toString())){s.debug=false;continue;}
            var color=l.type()==LocationDefinition.Type.HIDDEN?new Vector3f(.65f,.1f,.85f):l.type()==LocationDefinition.Type.NARRATIVE_TRANSFORMABLE?new Vector3f(1,.5f,0):new Vector3f(0,1,0);
            if(r.validate(l.bounds().dimension(),p.serverLevel().getMinBuildHeight(),p.serverLevel().getMaxBuildHeight()).findings().stream().anyMatch(f->f.severity()==ValidationReport.Severity.ERROR||f.severity()==ValidationReport.Severity.FATAL))color=new Vector3f(1,0,0);
            outline(p,l.bounds(),p.getY()+.1,color);var anchors=new ArrayList<>(new TreeMap<>(l.anchors()).values());for(int n=0;n<Math.min(8,anchors.size());n++){var a=anchors.get(Math.floorMod(server.getTickCount()/20+n,anchors.size()));particle(p,a.x()+.5,a.y()+.5,a.z()+.5,new Vector3f(0,.3f,1));}
            var zones=new ArrayList<>(r.zones(l.id()).values());if(!zones.isEmpty()){var z=zones.get(Math.floorMod(server.getTickCount()/20,zones.size()));outline(p,z.bounds(),p.getY()+.3,z.kind()==LocationZone.Kind.TRANSFORMABLE?new Vector3f(1,.5f,0):new Vector3f(1,0,0));}
        }
    }
    private static void outline(ServerPlayer p,WorldPrepArea b,double y,Vector3f color){for(int n=0;n<=8;n++){double t=n/8.0,x=b.minX()+(b.maxX()+1.0-b.minX())*t,z=b.minZ()+(b.maxZ()+1.0-b.minZ())*t;particle(p,x,y,b.minZ(),color);particle(p,x,y,b.maxZ()+1.0,color);particle(p,b.minX(),y,z,color);particle(p,b.maxX()+1.0,y,z,color);}}
    private static void particle(ServerPlayer p,double x,double y,double z,Vector3f color){p.serverLevel().sendParticles(p,new DustParticleOptions(color,1),true,x,y,z,1,0,0,0,0);}
    private static int export(CommandSourceStack source,Optional<UUID> destination){
        if(EXPORTING.contains(source.getServer()))throw new IllegalStateException("Location export already pending for this server");
        var level=source.getLevel();var r=registry(source);var data=destination.isPresent()?r.directorJson(destination.get(),level.dimension().location().toString(),level.getMinBuildHeight(),level.getMaxBuildHeight()):r.authoringJson();
        var p=source.getEntity() instanceof ServerPlayer player?player:null;var state=p==null?null:state(p);if(state!=null&&state.exporting)throw new IllegalStateException("Location export already pending");if(state!=null)state.exporting=true;
        var revision=r.revision();var root=source.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();var directory=root.resolve("worldprep-exports/locations");
        var dimension=level.dimension().location().toString().replace(':','_').replace('/','_');var name=dimension+"-"+(destination.map(UUID::toString).orElse("authoring"))+"-"+revision+"-"+PlanFingerprint.ofBytes(data).value()+".json";
        say(source,"Location export queued: immutable revision="+revision+" bytes="+data.length+"; no world mutation or Director import");
        EXPORTING.add(source.getServer());
        CompletableFuture.supplyAsync(()->{try{return write(directory.resolve(name),data);}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}})
            .whenComplete((path,error)->source.getServer().execute(()->{EXPORTING.remove(source.getServer());if(state!=null)state.exporting=false;if(p!=null&&(source.getServer().getPlayerList().getPlayer(p.getUUID())!=p||!p.hasPermissions(2)))return;
                if(error==null)say(source,"Location export written: "+path+" sha256="+PlanFingerprint.ofBytes(data).value());else source.sendFailure(Component.literal("Location export failed: "+error.getClass().getSimpleName()));}));return 1;
    }
    private static Path write(Path target,byte[] bytes)throws IOException{
        Path directory=target.getParent();for(Path p=directory;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink export directory refused");Files.createDirectories(directory);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||!Arrays.equals(Files.readAllBytes(target),bytes))throw new IOException("Existing export identity differs");return target;}
        Path temporary=Files.createTempFile(directory,"location-",".tmp");try{try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var input=ByteBuffer.wrap(bytes);while(input.hasRemaining())channel.write(input);channel.force(true);}Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);return target;}finally{Files.deleteIfExists(temporary);}
    }
}
