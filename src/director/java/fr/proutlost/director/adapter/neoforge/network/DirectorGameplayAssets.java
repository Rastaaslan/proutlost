package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-safe authored asset manifest. Vanilla sounds retain their native bytes and event IDs. */
public final class DirectorGameplayAssets {
 private DirectorGameplayAssets(){}
 public static final ResourceId WARNING=new ResourceId("proutlost:warning/camp_growth");
 public static final ResourceId LANDSLIDE_WARNING=new ResourceId("proutlost:test_landslide_warning");
 public static final String WARNING_RESOURCE="proutlost_director:textures/perception/warning.png";
 public static final Fingerprint WARNING_HASH=packagedHash(WARNING_RESOURCE);
 public record Spec(PerceptionKind kind,String resource,Map<String,Fingerprint> dependencies,Fingerprint hash) {
  public Spec {dependencies=Map.copyOf(dependencies);}
 }
 private static Fingerprint packagedHash(String resource) {
  String[] p=resource.split(":",2);
  try(var input=DirectorGameplayAssets.class.getResourceAsStream("/assets/"+p[0]+"/"+p[1])){
   if(input==null)throw new IllegalStateException("bundled asset missing "+resource);
   return BlobContent.hash(input.readAllBytes());
  }catch(IOException e){throw new IllegalStateException(e);}
 }
 private static Spec texture(PerceptionKind kind,String resource) {
  var hash=packagedHash(resource);return new Spec(kind,resource,Map.of(resource,hash),hash);
 }
 private static Spec sound(String event,Map<String,String> files) {
  Map<String,Fingerprint> dependencies=new TreeMap<>();
  dependencies.put("minecraft:sounds.json",new Fingerprint("51b23584d267ba37d3a1bafd4f24d3dda07672ab91672ea6f7a1bc86067a1a6b"));
  files.forEach((p,h)->dependencies.put(p,new Fingerprint(h)));
  var definition=new StringBuilder("proutlost-director:vanilla-sound-v1\n").append(event).append('\n');
  dependencies.forEach((p,h)->definition.append(p).append('=').append(h.value()).append('\n'));
  return new Spec(PerceptionKind.SOUND,event,dependencies,
   BlobContent.hash(definition.toString().getBytes(StandardCharsets.UTF_8)));
 }
 private static Map<ResourceId,Spec> manifest() {
  var result=new LinkedHashMap<ResourceId,Spec>();
  result.put(WARNING,texture(PerceptionKind.SYMBOL,WARNING_RESOURCE));
  result.put(new ResourceId("proutlost:test_fire_warning"),texture(PerceptionKind.SYMBOL,WARNING_RESOURCE));
  result.put(new ResourceId("proutlost_director:lab_choice_symbol"),texture(PerceptionKind.SYMBOL,WARNING_RESOURCE));
  result.put(LANDSLIDE_WARNING,texture(PerceptionKind.SYMBOL,WARNING_RESOURCE));
  result.put(new ResourceId("proutlost:test_mist"),texture(PerceptionKind.LOCAL_WEATHER,
   "proutlost_director:textures/perception/mist.png"));
  result.put(new ResourceId("proutlost:test_silhouette"),texture(PerceptionKind.APPARITION,
   "proutlost_director:textures/perception/silhouette.png"));
  result.put(new ResourceId("minecraft:block.campfire.crackle"),sound("minecraft:block.campfire.crackle",Map.of(
   "minecraft:sounds/block/campfire/crackle1.ogg","e2641d560fa30b55cc26690e68133d9f7d72cf53a487a3141e849ca420628227",
   "minecraft:sounds/block/campfire/crackle2.ogg","f45d0e53ef0f43bc1dd5a4368c7d115ae8cb3b9d429c5d07633aae6dd388afb4",
   "minecraft:sounds/block/campfire/crackle3.ogg","5406fe28c1de01ef4ba6155390952ae4231ba4de2d8cedb7d35ad927e8c32584",
   "minecraft:sounds/block/campfire/crackle4.ogg","8f21d3a9dcc854a57877afc93b64b6d891d88257dd6bd6bb7878d73209f0e1cc",
   "minecraft:sounds/block/campfire/crackle5.ogg","b3e6bf531fecab3ccc252476115eb777010b10857ff34fa65f07b4370db01158",
   "minecraft:sounds/block/campfire/crackle6.ogg","26cd70b9d4a8babd574d091787be218d09ebdfff6dbfc6dd3de40eda2dd1a997")));
  result.put(new ResourceId("minecraft:block.gravel.step"),sound("minecraft:block.gravel.step",Map.of(
   "minecraft:sounds/step/gravel1.ogg","0ab687ad74071c1ee9e836b7538222528ebee3a1d41c3550f60d2ced7cbaa2e3",
   "minecraft:sounds/step/gravel2.ogg","f3d5531fe16ff4d1ef36c1e9468d504cee699d9932c8b5213d615f378e4f74dc",
   "minecraft:sounds/step/gravel3.ogg","cffef11c1c93b087b355a2e486675f3b22d8be837b3151bf1761384a299824e0",
   "minecraft:sounds/step/gravel4.ogg","ec03b8fedb57670eb6762f10c415300f947281205c1412cbedd8f0bc094f400a")));
  result.put(new ResourceId("minecraft:entity.breeze.idle_air"),sound("minecraft:entity.breeze.idle_air",Map.of(
   "minecraft:sounds/mob/breeze/idle_air1.ogg","728858dfdeca0db054aafd15db1e9c92a437753483f49fe102f306240093cf1f",
   "minecraft:sounds/mob/breeze/idle_air2.ogg","0c0d335168e8ae5c1acd8be0c2cf2a87660414a0b88f4c830c9896dd3b790fd5",
   "minecraft:sounds/mob/breeze/idle_air3.ogg","3579de069dd3fe25de628137b7d9dc3a8845bb6af39349091bb5ec14020b55c4",
   "minecraft:sounds/mob/breeze/idle_air4.ogg","99c9d805f970d43f03be5c1c1850f1cc31850fd413911435b15421dbcda2345f")));
  return Map.copyOf(result);
 }
 private static final Map<ResourceId,Spec> SPECS=manifest();
 public static Map<ResourceId,Spec> specs(){return SPECS;}
 public static Map<ResourceId,Fingerprint> hashes(){
  Map<ResourceId,Fingerprint> hashes=new HashMap<>();SPECS.forEach((id,s)->hashes.put(id,s.hash()));return Map.copyOf(hashes);
 }
 public static Map<ResourceId,DirectorPerceptionPrimitiveEncoder.Asset> encoderAssets(){
  Map<ResourceId,DirectorPerceptionPrimitiveEncoder.Asset> assets=new HashMap<>();
  SPECS.forEach((id,s)->assets.put(id,new DirectorPerceptionPrimitiveEncoder.Asset(s.kind(),s.hash(),ProviderMode.PRODUCTION)));
  return Map.copyOf(assets);
 }
 public static Set<PerceptionKind> kinds(){return Set.of(PerceptionKind.SYMBOL,PerceptionKind.SOUND,PerceptionKind.LOCAL_WEATHER,PerceptionKind.APPARITION);}
 public static ResourceId asset(EffectIntent intent){
  return switch(intent.typedArgs()){
   case Warning w->w.profile();case TargetSound s->s.sound();
   case LocalWeather w->w.profile();case Apparition a->a.model();
   case SharedChoice ignored->new ResourceId("proutlost_director:lab_choice_symbol");
   default->throw new IllegalArgumentException("unsupported perception args");
  };
 }
}
