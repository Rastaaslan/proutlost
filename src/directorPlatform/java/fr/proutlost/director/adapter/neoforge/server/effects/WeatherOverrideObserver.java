package fr.proutlost.director.adapter.neoforge.server.effects;

import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.proutlost.director.api.model.DimensionId;
import fr.proutlost.director.api.model.Epoch;
import fr.proutlost.director.api.model.Revision;
import java.util.*;
import java.util.function.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-actor weather authority observer, pinned to Minecraft 1.21.1.
 * Revisions fence real observed control attempts, rule changes and non-native state.
 * They do not claim a command succeeded. Only native timer evolution is ignored.
 * Call directorWrite only around this lane's actual weather binding apply.
 * Boot fencing invalidates old leases; it does not prove old apply/restore outcome.
 * Direct third-party writes matching reachable vanilla state remain indistinguishable.
 */
public final class WeatherOverrideObserver implements AutoCloseable {
 private static final long STRIDE=1L<<32;
 private static final Map<MinecraftServer,WeatherOverrideObserver> ACTIVE=new IdentityHashMap<>();
 private final MinecraftServer server;
 private final long bootBase;
 private final Map<DimensionId,Entry> entries=new HashMap<>();
 private IEventBus bus;
 private boolean closed;
 private final Consumer<CommandEvent> commands=this::command;
 private final Consumer<SleepFinishedTimeEvent> sleeps=this::sleep;
 private final Consumer<ServerTickEvent.Post> ticks=this::post;
 private final Consumer<ServerStoppedEvent> stops=this::stop;
 private record Native(long tick,int clear,int rain,int thunder,boolean raining,
                       boolean thundering,boolean cycle,boolean daylight) {}
 private static final class Entry {
  Native last; String core; long changes; int writing; String reason="BOOT_FENCE";
  Entry(Native initial,String core){last=initial;this.core=core;}
 }
 public WeatherOverrideObserver(MinecraftServer server,Epoch bootEpoch){
  this.server=Objects.requireNonNull(server);
  bootBase=Math.multiplyExact(Objects.requireNonNull(bootEpoch).value(),STRIDE);
  actor();
  synchronized(ACTIVE){if(ACTIVE.putIfAbsent(server,this)!=null)
   throw new IllegalStateException("weather observer already installed");}
  poll();
 }
 private void actor(){if(closed||!server.isSameThread())throw new IllegalStateException("weather observer actor");}
 public void register(IEventBus eventBus){
  actor();if(bus!=null)throw new IllegalStateException("weather observer registered");
  bus=Objects.requireNonNull(eventBus);
  bus.addListener(commands);bus.addListener(sleeps);bus.addListener(ticks);bus.addListener(stops);
 }
 private static DimensionId id(ServerLevel level){return new DimensionId(level.dimension().location().toString());}
 private ServerLevel level(DimensionId dimension){
  for(var l:server.getAllLevels())if(id(l).equals(dimension)&&l.dimensionType().hasSkyLight())return l;
  throw new IllegalArgumentException("weather dimension unavailable: "+dimension);
 }
 private static Native read(ServerLevel level){
  var d=(ServerLevelData)level.getLevelData();var rules=level.getGameRules();
  return new Native(level.getGameTime(),d.getClearWeatherTime(),d.getRainTime(),d.getThunderTime(),
    d.isRaining(),d.isThundering(),rules.getBoolean(GameRules.RULE_WEATHER_CYCLE),
    rules.getBoolean(GameRules.RULE_DAYLIGHT));
 }
 private static String coreIdentity(ServerLevel level){
  if(!net.neoforged.fml.ModList.get().isLoaded("weathercore"))return "";
  try{return (String)Class.forName("fr.proutlost.director.adapter.neoforge.server.effects.weathercore.WeatherCoreIntegration")
    .getMethod("globalIdentity",ServerLevel.class).invoke(null,level);}
  catch(ReflectiveOperationException failed){throw new IllegalStateException("Core identity observer unavailable",failed);}
 }
 private Entry observe(ServerLevel level){
  var now=read(level);var core=coreIdentity(level);var e=entries.computeIfAbsent(id(level),unused->new Entry(now,core));
  if(e.writing==0){
   if(e.last.cycle()!=now.cycle()||e.last.daylight()!=now.daylight())bump(e,"ACTUAL_GAMERULE_CHANGE");
   else if(!Objects.equals(e.core,core))bump(e,"CORE_GLOBAL_IDENTITY_CHANGED");
   else if(!nativeEvolution(e.last,now))bump(e,"NON_NATIVE_WEATHER_STATE");
   e.last=now;e.core=core;
  }
  return e;
 }
 private void bump(Entry e,String reason){
  if(e.changes==STRIDE-1)throw new IllegalStateException("weather revision range exhausted");
  e.changes++;e.reason=reason;
 }
 public Revision revision(DimensionId dimension){
  actor();var e=observe(level(dimension));return new Revision(Math.addExact(bootBase,e.changes));
 }
 public String lastReason(DimensionId dimension){actor();return observe(level(dimension)).reason;}
 public void poll(){
  actor();for(var level:server.getAllLevels())if(level.dimensionType().hasSkyLight())observe(level);
 }
 public void runDirectorWrite(DimensionId dimension,Runnable write){
  directorWrite(dimension,()->{write.run();return null;});
 }
 public <T>T directorWrite(DimensionId dimension,Supplier<T> write){
  actor();var level=level(dimension);var e=observe(level);e.writing++;
  try{return Objects.requireNonNull(write).get();}
  finally{
   e.writing--;
   if(e.writing==0){
    var now=read(level);
    if(e.last.cycle()!=now.cycle()||e.last.daylight()!=now.daylight())bump(e,"ACTUAL_GAMERULE_CHANGE_DURING_DIRECTOR");
    e.last=now;e.core=coreIdentity(level);
   }
  }
 }
 private void invalidate(ServerLevel level,String reason){
  if(!level.dimensionType().hasSkyLight())return;
  var e=observe(level);bump(e,reason);e.last=read(level);
 }
 private void command(CommandEvent event){
  var context=event.getParseResults().getContext();
  if(context.getSource().getServer()!=server)return;
  actor();boolean weather=false,rule=false;
  for(CommandContextBuilder<CommandSourceStack> c=context;c!=null;c=c.getChild()){
   for(var parsed:c.getNodes())if(parsed.getNode() instanceof LiteralCommandNode<?>){
    var name=parsed.getNode().getName();
    weather|=name.equals("weather")||name.equals("toggledownfall");
    rule|=name.equals("doWeatherCycle")||name.equals("doDaylightCycle");
   }
  }
  // This is an ATTEMPT fence. Redirected execute-in sources need not be resolved
  // in ParseResults yet; the Core identity witness observes the actual target
  // manager again before any permitted apply/restore, including same-flags changes.
  if(weather)invalidate(net.neoforged.fml.ModList.get().isLoaded("weathercore")?
    context.getSource().getLevel():server.overworld(),"PARSED_WEATHER_COMMAND_ATTEMPT");
  if(rule)for(var l:server.getAllLevels())if(l.dimensionType().hasSkyLight())
   invalidate(l,"PARSED_WEATHER_RULE_COMMAND_ATTEMPT");
 }
 private void post(ServerTickEvent.Post event){if(event.getServer()==server)poll();}
 private void stop(ServerStoppedEvent event){if(event.getServer()==server)close();}
 private void sleep(SleepFinishedTimeEvent event){
  if(event.getLevel() instanceof ServerLevel l&&l.getServer()==server){
   actor();
   if(l.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)&&l.isRaining())
    invalidate(l,"ACTUAL_SLEEP_WEATHER_RESET_CONTEXT");
  }
 }
 private static boolean same(Native a,Native b){
  return a.clear()==b.clear()&&a.rain()==b.rain()&&a.thunder()==b.thunder()
    &&a.raining()==b.raining()&&a.thundering()==b.thundering();
 }
 /** Reachability only, never predicts/consumes native RNG. False relinquishes lease. */
 private static boolean nativeEvolution(Native a,Native b){
  long dt=b.tick()-a.tick();
  if(dt<0||a.clear()<0||a.rain()<0||a.thunder()<0||b.clear()<0||b.rain()<0||b.thunder()<0)return false;
  if(!a.cycle()||dt==0)return same(a,b);
  int rain=a.rain(),thunder=a.thunder();boolean raining=a.raining(),thundering=a.thundering();
  if(a.clear()>0){
   if(dt<=a.clear()){
    int rt=dt==1?(raining?0:1):1,tt=dt==1?(thundering?0:1):1;
    return b.clear()==a.clear()-dt&&!b.raining()&&!b.thundering()&&b.rain()==rt&&b.thunder()==tt;
   }
   dt-=a.clear();rain=a.clear()==1?(raining?0:1):1;
   thunder=a.clear()==1?(thundering?0:1):1;raining=false;thundering=false;
  }
  return b.clear()==0&&channel(rain,raining,b.rain(),b.raining(),dt,false)
    &&channel(thunder,thundering,b.thunder(),b.thundering(),dt,true);
 }
 private static boolean channel(int before,boolean flag,int after,boolean afterFlag,long dt,boolean thunder){
  if(dt==0)return before==after&&flag==afterFlag;
  if(before>0&&dt<before)return after==before-dt&&afterFlag==flag;
  if(before>0&&dt==before)return after==0&&afterFlag!=flag;
  // At most one sample; minimum native thunder duration is 3600 ticks.
  if(dt>=3600)return false;
  boolean nextFlag=before==0?flag:!flag;
  long spent=dt-(before==0?1L:before+1L);
  int min=nextFlag&&thunder?3600:12000;
  int max=nextFlag?(thunder?15600:24000):180000;
  return spent>=0&&afterFlag==nextFlag&&after>=min-spent&&after<=max-spent;
 }
 @Override public void close(){
  if(closed)return;actor();
  if(bus!=null){bus.unregister(commands);bus.unregister(sleeps);bus.unregister(ticks);bus.unregister(stops);bus=null;}
  synchronized(ACTIVE){ACTIVE.remove(server,this);}entries.clear();closed=true;
 }
}
