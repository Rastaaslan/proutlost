package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.network.*;
import fr.proutlost.director.api.codec.CanonicalJsonV1;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/** DRAFT trusted server bridge. No input is a client-authored readable/consent flag.
 * Requires the receivedAndLive/liveWarningReceived additions described beside this draft. */
public final class NativeDisasterWarningProof {
 private NativeDisasterWarningProof(){}
 public record Marker(PlayerId player,String corner,ResourceId profile,
   PerceptionTransform transform,PerceptionHeader header,Tick sentTick){}
 public record Captured(WarningEvidence evidence,byte[] proof){
  public Captured {proof=proof.clone();}
  @Override public byte[] proof(){return proof.clone();}
 }
 private static final Set<String> CORNERS=Set.of("NW","NE","SE","SW");
 private static final ModelCodec<PerceptionHeader> HEADER=new ModelCodec<>(PerceptionHeader.class);
 private static final ModelCodec<PerceptionTransform> TRANSFORM=new ModelCodec<>(PerceptionTransform.class);
 private static JsonValue.Text text(Object value){return new JsonValue.Text(value.toString());}
 private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
 /** Call after all marker EventRunners are COMMITTED, with their actual sent ticks/headers. */
 public static Captured capture(MinecraftServer server,DisasterInstance state,Tick warnEntry,
   Set<PlayerId> actualAudience,Epoch audienceRevision,DurationTicks lead,Tick now,List<Marker> markers){
  require(server.isSameThread()&&state.stage()==DisasterStage.WARN,"native WARN actor/state");
  require(!actualAudience.isEmpty()&&lead.value()>0&&now.compareTo(warnEntry)>0,"WARN audience/time");
  require(state.envelope().bounds().size()==1,"one surveyed rectangular perimeter required");
  var bounds=state.envelope().bounds().getFirst();
  var seen=new HashMap<PlayerId,Set<String>>();var receipts=new ArrayList<JsonValue>();
  WorldId world=null;Epoch boot=null;
  for(var marker:markers){
   require(actualAudience.contains(marker.player())&&CORNERS.contains(marker.corner()),"foreign marker audience/corner");
   var asset=Objects.requireNonNull(DirectorGameplayAssets.specs().get(marker.profile()),"unshipped warning asset");
   require(asset.kind()==PerceptionKind.SYMBOL,"warning marker is not SYMBOL");
   var current=DirectorNativeNetworkBootstrap.qualifiedRecipient(server,marker.player(),
    PerceptionKind.SYMBOL,marker.profile(),asset.hash()).orElseThrow(()->new IllegalStateException("renderer/session unavailable"));
   var header=marker.header();
   require(current.worldId().equals(header.worldId())&&current.bootEpoch().equals(header.bootEpoch())
    &&current.connectionEpoch().equals(header.connectionEpoch())&&current.dimension().equals(header.dimension())
    &&header.instanceId().equals(state.eventInstanceId()),"stale/foreign marker header");
   require(marker.sentTick().compareTo(warnEntry)>0&&marker.sentTick().compareTo(now)<=0,"WATCH-era marker");
   require(Math.addExact(marker.sentTick().value(),header.remainingTtlTicks().value())
    >Math.addExact(now.value(),lead.value()),"perimeter cannot remain live for lead");
   require(DirectorNativeNetworkBootstrap.liveWarningReceived(server,marker.player(),header,marker.profile(),marker.transform()),"marker not currently live and received");
   boolean east=marker.corner().equals("NE")||marker.corner().equals("SE");
   boolean south=marker.corner().equals("SW")||marker.corner().equals("SE");
   double x=east?bounds.maxXExclusive()-.25:bounds.minX()+.25;
   double z=south?bounds.maxZExclusive()-.25:bounds.minZ()+.25;
   require(Math.abs(marker.transform().x()-x)<1e-6&&Math.abs(marker.transform().z()-z)<1e-6
    &&Math.abs(marker.transform().y()-(bounds.minY()+.8))<1e-6,"marker not at sealed perimeter corner");
   require(seen.computeIfAbsent(marker.player(),p->new HashSet<>()).add(marker.corner()),"duplicate marker");
   if(world==null){world=header.worldId();boot=header.bootEpoch();}
   require(world.equals(header.worldId())&&boot.equals(header.bootEpoch()),"mixed world/boot");
   receipts.add(new JsonValue.ObjectValue(Map.of(
    "player",text(marker.player().value()),"marker",text(marker.corner()),
    "header",text(Base64.getEncoder().encodeToString(HEADER.encode(header))),
    "transform",text(Base64.getEncoder().encodeToString(TRANSFORM.encode(marker.transform()))),
    "sent_tick",text(marker.sentTick().value()),"profile",text(marker.profile().value()),"received",new JsonValue.Bool(true))));
  }
  require(seen.keySet().equals(actualAudience),"missing affected player markers");
  for(var corners:seen.values())require(corners.equals(CORNERS),"incomplete live perimeter");
  var fields=new TreeMap<String,JsonValue>();
  fields.put("schema",text(1));fields.put("world",text(world.value()));fields.put("boot",text(boot.value()));
  fields.put("disaster",text(state.disasterId().value()));fields.put("event",text(state.eventInstanceId().value()));
  fields.put("warn_entered",text(warnEntry.value()));fields.put("deployed",text(now.value()));
  fields.put("lead",text(lead.value()));fields.put("audience_revision",text(audienceRevision.value()));
  fields.put("envelope_hash",text(BlobContent.hash(new ModelCodec<>(Footprint.class).encode(state.envelope())).value()));
  fields.put("receipts",new JsonValue.Array(receipts));
  var proof=CanonicalJsonV1.encode(new JsonValue.ObjectValue(fields));
  // Conservative lead starts when all exact renderer receipts have been observed.
  return new Captured(new WarningEvidence(actualAudience,now,lead,BlobContent.hash(proof),
   audienceRevision,true,true),proof);
 }
}
