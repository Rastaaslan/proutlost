package fr.proutlost.director.bootstrap;

import fr.proutlost.director.neoforge.DirectorStandaloneMod;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.DurableStore;
import java.util.*;
import net.minecraft.server.MinecraftServer;

/**
 * Real J03 input gate on the Minecraft actor. This reads a concrete WORLD
 * aggregate from J04 and requires a verified PRODUCTION J02 catalog before
 * it can construct DecisionInput. It never fabricates missing world state,
 * treats missing WAL records as safety evidence, or grants J05 authority.
 *
 * "Ready" would authorize pure evaluation only, not physical admission.
 * The latest laboratory still has unapproved J02 candidates, so the native
 * gate must remain blocked even if unrelated tests install world fixtures.
 */
public final class DirectorNativeDecisionReadiness {
    private DirectorNativeDecisionReadiness() {}

    public record Snapshot(boolean j04StoreRecovered,
                           boolean persistedWorldStateAvailable,
                           boolean observationAvailable,
                           boolean memoryAvailable,
                           boolean qualifiedCatalogAvailable,
                           boolean readyForPureEvaluation,
                           boolean permitsGameplay,
                           Set<String> blockers) {
        public Snapshot {
            blockers=Set.copyOf(blockers);
            if(permitsGameplay ||
               (persistedWorldStateAvailable && !j04StoreRecovered) ||
               (readyForPureEvaluation && (!persistedWorldStateAvailable ||
                   !observationAvailable || !memoryAvailable || !qualifiedCatalogAvailable)) ||
               (readyForPureEvaluation!=blockers.isEmpty()))
                throw new IllegalArgumentException("unqualified J03 native admission");
        }
    }

    /** Single actual server-actor capture: no fake empty catalog is accepted. */
    public static Snapshot inspect(MinecraftServer server) {
        Objects.requireNonNull(server);
        if(!server.isSameThread())throw new IllegalStateException("J03 actor");
        var lifecycle=DirectorStandaloneMod.directorLifecycle();
        var reasons=new TreeSet<String>();
        var observation=lifecycle.observationSnapshot(server);
        if(observation.isEmpty())reasons.add("J01_ACTOR_SNAPSHOT_UNAVAILABLE");
        else if(observation.orElseThrow().serverHealth()!=HealthMode.NORMAL)
            reasons.add("J01_SERVER_HEALTH_NOT_NORMAL");
        var memory=lifecycle.memoryProjectionView(server);
        if(memory.isEmpty() ||
           !memory.orElseThrow().complete() ||
           memory.orElseThrow().failClosed())
            reasons.add("J04_MEMORY_NOT_VERIFIED");
        // J02's CANDIDATE checksum checker is a TEST_ONLY boundary, not
        // a CatalogPort or production primitive provider.
        reasons.add("J02_PRODUCTION_CATALOG_PROVIDER_MISSING");
        var wal=lifecycle.labReadOnlyStoreSnapshot(server);
        // A recovered store is not proof that its WORLD record was readable.
        // Keep the DTO negative until the exact world/session was validated.
        boolean verifiedWorld=false;
        if(wal.isEmpty()){
            reasons.add("J04_RECOVERED_SESSION_UNAVAILABLE");
            reasons.add("J03_DURABLE_WORLD_STATE_MISSING");
        }else {
            DurableStore store=wal.orElseThrow();
            try {
                // Pin a single J04 authority across the entire actor read.
                var authority=store.session();
                var identity=server.overworld().getDataStorage().computeIfAbsent(
                    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.FACTORY,
                    fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity.KEY);
                if(!identity.worldId().equals(authority.worldId())){
                    reasons.add("J04_WORLD_IDENTITY_MISMATCH");
                }else{
                    try{
                        var key=new AggregateKey(AggregateKind.WORLD,identity.worldId().value());
                        var query=new StoreQuery(identity.worldId(),Set.of(key),
                            new Sequence(0),Optional.empty(),1);
                        var result=store.view(query);
                        ReadContracts.validate(result,query,authority);
                        if(result.status()!=ReadStatus.AVAILABLE){
                            reasons.add("J03_DURABLE_WORLD_STATE_UNAVAILABLE");
                        }else{
                            var matches=result.page().orElseThrow().aggregates();
                            if(matches.size()!=1 ||
                               !(matches.getFirst().state() instanceof WorldAggregate world) ||
                               !world.value().worldId().equals(identity.worldId()) ||
                               !world.value().storeGeneration().equals(authority.storeGeneration()) ||
                               !world.value().bootEpoch().equals(authority.bootEpoch()))
                                reasons.add("J03_DURABLE_WORLD_STATE_MISSING_OR_STALE");
                            else if(!authority.equals(store.session()))
                                reasons.add("J04_SESSION_CHANGED_DURING_WORLD_READ");
                            else verifiedWorld=true;
                        }
                    }catch(RuntimeException rejected){
                        reasons.add("J03_DURABLE_WORLD_STATE_INVALID");
                    }
                }
            }catch(RuntimeException unavailable){
                // Invalid/stale J04 reads are ordinary readiness blockers,
                // not an exception that may escape an admin/F8 status query.
                verifiedWorld=false;
                reasons.add("J04_SESSION_OR_WORLD_PROBE_INVALID");
            }
        }
        if(lifecycle.snapshot(server).admitsEvents())
            throw new IllegalStateException("unqualified live gameplay!");
        return new Snapshot(wal.isPresent(),verifiedWorld,
            observation.isPresent(),
            memory.isPresent() && memory.orElseThrow().complete() &&
                !memory.orElseThrow().failClosed(),
            false,reasons.isEmpty(),false,reasons);
    }
}
