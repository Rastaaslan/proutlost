package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * J02/J06 typed perception translation, with no sender, world write or permit.
 * The caller MUST obtain its header, session, roles and approved asset manifest
 * from authoritative server/J04 state. Creating a DTO is NOT a durable PREPARE
 * or authorization to call PerceptionPort.upsert.
 *
 * Unsupported J02 primitives and effects without a bounded 1:1 J06
 * representation fail closed. No gameplay path currently installs this encoder.
 */
public final class DirectorPerceptionPrimitiveEncoder {
    private DirectorPerceptionPrimitiveEncoder() {}

    public record Asset(PerceptionKind kind, Fingerprint hash, ProviderMode mode) {
        public Asset {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(hash);
            Objects.requireNonNull(mode);
        }
    }

    public static PerceptionIntent encode(EffectIntent intent,
                                          Map<String,PlayerId> serverRoles,
                                          SessionIdentity session,
                                          PerceptionHeader header,
                                          PerceptionTransform transform,
                                          Map<ResourceId,Asset> qualifiedAssets) {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(serverRoles);
        Objects.requireNonNull(session);
        Objects.requireNonNull(header);
        Objects.requireNonNull(transform);
        Objects.requireNonNull(qualifiedAssets);
        Checks.require(intent.version()==1, "unapproved primitive revision");
        Checks.require(header.instanceId().equals(intent.instanceId())
            && header.effectId().value().equals(intent.operationToken().value()),
            "unbound WAL operation and perception identity");
        Checks.require(header.worldId().equals(session.worldId())
            && header.bootEpoch().equals(session.bootEpoch())
            && header.connectionEpoch().equals(session.connectionEpoch())
            && header.dimension().equals(session.dimension())
            && intent.expectedFootprint().dimension().equals(session.dimension()),
            "stale or foreign J06 session/footprint");
        Checks.require(header.remainingTtlTicks().value()>0
            && header.remainingTtlTicks().value()<=1200, "J06 TTL limit");
        // A valid WAL operation cannot be rendered at arbitrary world coordinates.
        // A loaded-chunk claim provides the minimum actor-verified spatial scope;
        // when the plan also seals cells/bounds, its origin must be inside them.
        // Never infer the authorized position from a network-supplied transform.
        var footprint=intent.expectedFootprint();
        var originChunk=new ChunkRef(footprint.dimension(),
            (int)Math.floor(transform.x()/16.0),
            (int)Math.floor(transform.z()/16.0));
        Checks.require(footprint.loadedChunks().contains(originChunk),
            "J06 transform outside declared loaded chunk");
        if(!footprint.cells().isEmpty() || !footprint.bounds().isEmpty()) {
            int x=(int)Math.floor(transform.x());
            int y=(int)Math.floor(transform.y());
            int z=(int)Math.floor(transform.z());
            boolean inCell=footprint.cells().contains(new BlockPosition(
                footprint.dimension(),x,y,z));
            boolean inBounds=footprint.bounds().stream().anyMatch(box->
                x>=box.minX() && x<box.maxXExclusive() &&
                y>=box.minY() && y<box.maxYExclusive() &&
                z>=box.minZ() && z<box.maxZExclusive());
            Checks.require(inCell || inBounds,
                "J06 transform outside durable footprint cells/bounds");
        }

        String role;
        ResourceId assetId;
        PerceptionKind kind;
        PerceptionParameters parameters;
        PrimitiveArgs args=intent.typedArgs();
        switch(intent.primitive().value()) {
            case "proutlost:target_sound" -> {
                Checks.require(args instanceof TargetSound, "sound typed args");
                var sound=(TargetSound)args;
                // A single upsert is not a reliable representation of repeated
                // playback. Repeats require a separately audited multi-step plan.
                Checks.require(sound.repeats()==1, "unimplemented sound repetitions");
                role=sound.role();assetId=sound.sound();kind=PerceptionKind.SOUND;
                parameters=new SoundParameters(sound.volume(),1.0,sound.radiusBlocks());
            }
            case "proutlost:target_particles" -> {
                Checks.require(args instanceof TargetParticles, "particles typed args");
                var particles=(TargetParticles)args;
                role=particles.role();assetId=particles.particle();kind=PerceptionKind.PARTICLES;
                parameters=new ParticleParameters(particles.count(),particles.radiusBlocks());
            }
            case "proutlost:apparition" -> {
                Checks.require(args instanceof Apparition, "apparition typed args");
                var apparition=(Apparition)args;
                Checks.require(apparition.followTicks().value()>0
                    && apparition.followTicks().value()<=header.remainingTtlTicks().value(),
                    "apparition duration exceeds live authorization");
                role=apparition.role();assetId=apparition.model();kind=PerceptionKind.APPARITION;
                parameters=new ApparitionParameters(apparition.followTicks(),
                    apparition.maxDistanceBlocks());
            }
            case "proutlost:local_weather" -> {
                Checks.require(args instanceof LocalWeather, "local weather typed args");
                var weather=(LocalWeather)args;
                role=weather.role();assetId=weather.profile();kind=PerceptionKind.LOCAL_WEATHER;
                parameters=new WeatherParameters(weather.profile(),new UnitValue(1));
            }
            case "proutlost:shared_choice" -> {
                Checks.require(args instanceof SharedChoice, "choice typed args");
                var choice=(SharedChoice)args;
                Checks.require(choice.roles().equals(List.of("witness"))
                    && choice.choiceIds().equals(List.of(new ResourceId("proutlost:listen"),new ResourceId("proutlost:leave_trace")))
                    && choice.timeoutPolicy()==TimeoutPolicy.KEEP_UNCHANGED,"unsupported choice menu");
                role=choice.roles().getFirst();
                assetId=new ResourceId("proutlost_director:lab_choice_symbol");
                kind=PerceptionKind.SYMBOL;
                parameters=new SymbolParameters(assetId,new UnitValue(1));
            }
            case "proutlost:warning" -> {
                Checks.require(args instanceof Warning, "warning typed args");
                var warning=(Warning)args;
                Checks.require(warning.minimumLeadTicks().value()>0
                    && warning.minimumLeadTicks().value()<=header.remainingTtlTicks().value(),
                    "warning visible TTL shorter than mandatory lead");
                role=warning.role();assetId=warning.profile();kind=PerceptionKind.SYMBOL;
                parameters=new SymbolParameters(warning.profile(),new UnitValue(1));
            }
            default -> throw new IllegalArgumentException(
                "no qualified J06 representation for "+intent.primitive().value());
        }
        PlayerId target=serverRoles.get(role);
        Checks.require(target!=null && target.equals(session.playerId()),
            "J06 role not server-bound to authenticated target");
        Asset asset=qualifiedAssets.get(assetId);
        Checks.require(asset!=null && asset.mode()==ProviderMode.PRODUCTION
            && asset.kind()==kind, "missing or unapproved J06 asset");
        var payload=new PerceptionUpsert(header,kind,transform,
            assetId,asset.hash(),parameters);
        return new PerceptionIntent(session,target,payload);
    }
}
