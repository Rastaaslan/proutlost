package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.*;
import java.util.function.LongSupplier;

/** Render-only proxies: never inserts an Entity, ItemStack or block into ClientLevel.
 * Asset hashes must come from the resource reload preparation stage, not from server claims. */
public final class MinecraftPerceptionEffects implements PerceptionManager.Effects {
    public record Asset(PerceptionKind kind, ResourceLocation resource, Fingerprint hash) {}
    private static final class Visual {
        PerceptionUpsert effect; final long started; final Vec3 origin,playerOrigin;
        final List<Particle> particles=new ArrayList<>(); int emitted;
        ManagedSound sound; Vec3 lastPosition; boolean followEnded;
        Visual(PerceptionUpsert effect,long now,Vec3 player){this.effect=effect;started=now;origin=position(effect);playerOrigin=player;lastPosition=origin;}
    }
    private final Minecraft minecraft;
    private final Map<ResourceId,Asset> assets;
    private final Map<EffectId,Visual> visuals=new LinkedHashMap<>();
    private final LongSupplier clock;
    private final int particleBudget;
    public MinecraftPerceptionEffects(Minecraft minecraft,Map<ResourceId,Asset> verifiedAssets,LongSupplier clock,int particleBudget) {
        this.minecraft=minecraft;assets=Map.copyOf(verifiedAssets);this.clock=clock;
        Checks.require(particleBudget>0&&particleBudget<=4096,"particle budget");this.particleBudget=particleBudget;
    }
    private void thread(){Checks.require(minecraft.isSameThread(),"client main/render thread");}
    /** Only hashes already checked against local resource bytes are present.
     * No server-offered asset is ever inserted into the client registry. */
    public Map<ResourceId,Fingerprint> hashes(){
        Map<ResourceId,Fingerprint> result=new HashMap<>();
        assets.forEach((id,asset)->result.put(id,asset.hash()));
        return Map.copyOf(result);
    }
    public Map<ResourceId,VerifiedAssetNegotiation.Local> verifiedKinds(){
        Map<ResourceId,VerifiedAssetNegotiation.Local> result=new HashMap<>();
        assets.forEach((id,asset)->result.put(id,
            new VerifiedAssetNegotiation.Local(asset.kind(),asset.hash())));
        return Map.copyOf(result);
    }
    @Override public boolean available(PerceptionUpsert e){thread();Asset a=assets.get(e.assetId());if(a==null||a.kind()!=e.kind()||!a.hash().equals(e.assetHash())||minecraft.level==null||minecraft.player==null)return false;
        return switch(e.kind()){
            case SOUND -> BuiltInRegistries.SOUND_EVENT.containsKey(a.resource())&&minecraft.getSoundManager().getSoundEvent(a.resource())!=null;
            case PARTICLES -> BuiltInRegistries.PARTICLE_TYPE.get(a.resource()) instanceof SimpleParticleType;
            case GHOST -> ((GhostParameters)e.parameters()).model().equals(e.assetId())&&BuiltInRegistries.BLOCK.containsKey(a.resource())&&!BuiltInRegistries.BLOCK.get(a.resource()).defaultBlockState().isAir();
            case SYMBOL -> ((SymbolParameters)e.parameters()).symbol().equals(e.assetId())&&minecraft.getResourceManager().getResource(a.resource()).isPresent();
            case LOCAL_WEATHER -> ((WeatherParameters)e.parameters()).profile().equals(e.assetId())&&minecraft.getResourceManager().getResource(a.resource()).isPresent();
            case APPARITION -> minecraft.getResourceManager().getResource(a.resource()).isPresent();
        };
    }
    @Override public void start(PerceptionUpsert e){thread();Checks.require(available(e),"missing client asset");
        var v=new Visual(e,clock.getAsLong(),minecraft.player.position());visuals.put(e.header().effectId(),v);
        if(e.kind()==PerceptionKind.SOUND){v.sound=new ManagedSound(e,assets.get(e.assetId()).resource());minecraft.getSoundManager().play(v.sound);}
    }
    @Override public void update(PerceptionUpsert e){thread();var v=visuals.get(e.header().effectId());Checks.require(v!=null,"missing effect");
        // A heartbeat cannot restart follow time or move the admitted footprint.
        Checks.require(position(e).equals(v.origin),"heartbeat footprint change");v.effect=e;
    }
    @Override public void stop(EffectId id){thread();var v=visuals.remove(id);if(v==null)return;
        // Clear the registry first and attempt every physical cleanup even if
        // sound or an individual particle throws. The manager then quarantines
        // this renderer on the propagated first failure.
        RuntimeException failure=null;
        if(v.sound!=null)try{minecraft.getSoundManager().stop(v.sound);}
            catch(RuntimeException e){failure=e;}
        for(var p:List.copyOf(v.particles))try{p.remove();}
            catch(RuntimeException e){if(failure==null)failure=e;}
        v.particles.clear();
        if(failure!=null)throw failure;
    }
    public void tick(){thread();int left=particleBudget;
        for(var v:visuals.values()){
            v.particles.removeIf(p->!p.isAlive());
            if(v.effect.parameters() instanceof ParticleParameters params){
                int count=Math.min(left,params.count()-v.emitted);var random=new SplittableRandom(v.effect.header().effectId().value().getLeastSignificantBits()+v.emitted);
                for(int i=0;i<count;i++){
                    var p=minecraft.particleEngine.createParticle((SimpleParticleType)BuiltInRegistries.PARTICLE_TYPE.get(assets.get(v.effect.assetId()).resource()),v.origin.x+(random.nextDouble()*2-1)*params.radius(),v.origin.y+(random.nextDouble()*2-1)*params.radius(),v.origin.z+(random.nextDouble()*2-1)*params.radius(),0,0,0);
                    if(p!=null)v.particles.add(p);
                }
                v.emitted+=count;left-=count;
            }
        }
    }
    private static Vec3 position(PerceptionUpsert e){var t=e.transform();return new Vec3(t.x(),t.y(),t.z());}
    public void render(RenderLevelStageEvent event){thread();if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES||minecraft.player==null)return;
        var buffers=minecraft.renderBuffers().bufferSource();var pose=event.getPoseStack();Vec3 camera=event.getCamera().getPosition();
        for(var v:List.copyOf(visuals.values())){
            var e=v.effect;if(e.kind()==PerceptionKind.SOUND||e.kind()==PerceptionKind.PARTICLES)continue;
            Vec3 pos=v.lastPosition;
            if(e.parameters() instanceof ApparitionParameters a && !v.followEnded && clock.getAsLong()-v.started<a.followTicks().value()){
                Vec3 shift=minecraft.player.position().subtract(v.playerOrigin);
                if(shift.length()>a.maximumDistance()){v.followEnded=true;}
                else {pos=v.origin.add(shift);v.lastPosition=pos;}
            }
            pose.pushPose();
            try {
                pose.translate(pos.x-camera.x,pos.y-camera.y,pos.z-camera.z);
                float scale=(float)e.transform().scale();pose.scale(scale,scale,scale);
                pose.mulPose(Axis.YP.rotationDegrees((float)e.transform().yaw()));pose.mulPose(Axis.XP.rotationDegrees((float)e.transform().pitch()));
                if(e.parameters() instanceof GhostParameters ghost){
                    var state=BuiltInRegistries.BLOCK.get(assets.get(e.assetId()).resource()).defaultBlockState();
                    var model=minecraft.getBlockRenderer().getBlockModel(state);
                    var consumer=buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
                    var random=RandomSource.create(0);var faces=new ArrayList<net.minecraft.core.Direction>(Arrays.asList(net.minecraft.core.Direction.values()));faces.add(null);
                    for(var face:faces){random.setSeed(0);for(var quad:model.getQuads(state,face,random))consumer.putBulkData(pose.last(),quad,1,1,1,(float)ghost.opacity().value(),LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);}
                } else {
                    float alpha=e.parameters() instanceof SymbolParameters s?(float)s.opacity().value():e.parameters() instanceof WeatherParameters w?(float)w.intensity().value():1;
                    var consumer=buffers.getBuffer(RenderType.entityTranslucent(assets.get(e.assetId()).resource()));
                    // Symbols/silhouettes use the authored texture. Local weather uses crossed spatial veil planes.
                    quad(consumer,pose.last(),alpha);
                    if(e.kind()==PerceptionKind.LOCAL_WEATHER){pose.mulPose(Axis.YP.rotationDegrees(90));quad(consumer,pose.last(),alpha);}
                }
            } finally {pose.popPose();}
        }
        buffers.endBatch();
    }
    private static void quad(VertexConsumer c,com.mojang.blaze3d.vertex.PoseStack.Pose p,float alpha){
        vertex(c,p,-.5f,0,0,1,alpha);vertex(c,p,.5f,0,1,1,alpha);vertex(c,p,.5f,1,1,0,alpha);vertex(c,p,-.5f,1,0,0,alpha);
    }
    private static void vertex(VertexConsumer c,com.mojang.blaze3d.vertex.PoseStack.Pose p,float x,float y,float u,float v,float a){c.addVertex(p,x,y,0).setColor(1f,1f,1f,a).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p,0,0,1);}
    private final class ManagedSound extends AbstractTickableSoundInstance {
        private final SoundParameters parameters;
        ManagedSound(PerceptionUpsert e,ResourceLocation id){super(BuiltInRegistries.SOUND_EVENT.get(id),SoundSource.AMBIENT,RandomSource.create());parameters=(SoundParameters)e.parameters();var t=e.transform();x=t.x();y=t.y();z=t.z();pitch=(float)parameters.pitch();attenuation=SoundInstance.Attenuation.NONE;looping=false;tick();}
        @Override public void tick(){if(minecraft.player==null){stop();return;}double d=minecraft.player.position().distanceTo(new Vec3(x,y,z));volume=(float)(parameters.volume()*(parameters.radius()==0?(d==0?1:0):Math.max(0,1-d/parameters.radius())));}
    }
}
