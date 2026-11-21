package fr.proutlost.director.bootstrap;

import fr.proutlost.director.adapter.neoforge.server.lifecycle.DirectorSavedIdentity;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.bootstrap.DirectorBootstrap;
import fr.proutlost.director.bootstrap.DirectorStorageIdentityGate;
import fr.proutlost.director.persistence.JournalStore;
import fr.proutlost.director.persistence.StoreOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Actor-owned, non-blocking J04 bootstrap gate. A verified store can be
 * published only as RECOVERED_DISABLED until full Director providers and
 * hard validators have been qualified. Never performs world effects.
 *
 * IO runs on J04's dedicated writer. Client ticks never join/get/wait.
 * The saved world projection is updated only after native WAL verification.
 */
public final class DirectorNativeStoreBootstrap {
    public enum Phase {
        NEW, OPENING, RECOVERED_DISABLED, FAILED_SAFE, CLOSING, CLOSED
    }
    public record View(Phase phase,boolean hasDurableStore,
                       boolean gameplayAdmitted,int missingPorts) {
        public View {
            Objects.requireNonNull(phase);
            if(gameplayAdmitted)throw new IllegalArgumentException("unqualified admission");
            if(missingPorts<0)throw new IllegalArgumentException("invalid port count");
        }
    }

    private final MinecraftServerActor owner;
    private final DirectorSavedIdentity identity;
    private final ProviderMode mode;
    private CompletableFuture<JournalStore> pendingOpen;
    private CompletionStage<Void> pendingClose;
    private boolean shutdownRequested;
    private JournalStore store;
    private Phase phase=Phase.NEW;
    private int missingPorts=11;

    /** Actors implement this with MinecraftServer.isSameThread; no Minecraft IO here. */
    public interface MinecraftServerActor {
        void assertActor();
        net.minecraft.server.MinecraftServer server();
    }

    public DirectorNativeStoreBootstrap(
            MinecraftServerActor owner,DirectorSavedIdentity identity) {
        this(owner,identity,ProviderMode.TEST_ONLY,null);
    }
    /** A production owner needs an explicitly approved configuration. This
     * does not approve a profile or grant event admission on its own. */
    public DirectorNativeStoreBootstrap(MinecraftServerActor owner,
            DirectorSavedIdentity identity,ProviderMode mode,ConfigView configuration) {
        this.owner=Objects.requireNonNull(owner);
        this.identity=Objects.requireNonNull(identity);
        this.mode=Objects.requireNonNull(mode);
        if(mode==ProviderMode.PRODUCTION &&
                (configuration==null || configuration.calibration()!=Calibration.APPROVED))
            throw new IllegalArgumentException("approved production configuration required");
    }
    private void actor(){owner.assertActor();}
    private void labActor(){
        actor();
        if(mode!=ProviderMode.TEST_ONLY)
            throw new IllegalStateException("laboratory capability unavailable in production");
    }

    /** The real recovered writer is shared by the production durable-store and
     * fact-bus ports. Never rotates or replaces SavedData/WAL identity. */
    /** Existing recovered J04 writer for provenance-checked explicit collections, in either provider mode. */
    public java.util.Optional<JournalStore> perceptionStore(){
        actor();return phase==Phase.RECOVERED_DISABLED&&store!=null&&!store.readOnlySafe()&&store.verifiedManifestAnchor().isPresent()
            ?java.util.Optional.of(store):java.util.Optional.empty();
    }
    public java.util.Optional<JournalStore> productionRecoveredStore() {
        actor();
        if(mode!=ProviderMode.PRODUCTION || phase!=Phase.RECOVERED_DISABLED ||
                store==null || store.readOnlySafe() || store.verifiedManifestAnchor().isEmpty())
            return java.util.Optional.empty();
        return java.util.Optional.of(store);
    }

    public void open(Path directory,StoreOptions options) {
        actor();
        if(phase!=Phase.NEW)throw new IllegalStateException("duplicate J04 boot");
        Objects.requireNonNull(directory);Objects.requireNonNull(options);
        phase=Phase.OPENING;
        try {
            pendingOpen=JournalStore.open(directory,identity.worldId(),options)
                .toCompletableFuture();
        } catch(RuntimeException e) {
            phase=Phase.FAILED_SAFE;
            throw e;
        }
    }
    /** Poll only on the game actor; no wait on writer thread. */
    public void poll() {
        actor();
        // A healthy recovery at boot is not perpetual J04 authority. A later
        // disk/fsync failure can revoke the live writer without changing the
        // bootstrap phase. Revoke consumers on this actor before its next tick;
        // never keep publishing a stale recovered store or boot epoch.
        if(phase==Phase.RECOVERED_DISABLED) {
            if(store==null || store.readOnlySafe()) {
                phase=Phase.FAILED_SAFE;
                missingPorts=11;
                System.out.println("DIRECTOR_NATIVE_J04_LIVE_WRITER_LOST_FAILED_SAFE");
                if(store!=null) {
                    pendingClose=store.closeAsync();
                    store=null;
                }
            }
            return;
        }
        if(phase!=Phase.OPENING || pendingOpen==null || !pendingOpen.isDone())
            return;
        try {
            store=pendingOpen.getNow(null);
            pendingOpen=null;
            if(store==null || store.readOnlySafe() ||
                !DirectorStorageIdentityGate.tryBindRecovered(
                    owner.server(),identity,store))
                throw new IllegalStateException("J04 recovery or WAL identity proof unavailable");
            var composition=DirectorBootstrap.compose(ProviderMode.PRODUCTION,
                List.of(new DirectorBootstrap.Provider(
                    store,ProviderMode.PRODUCTION,List.of(),List.of())));
            if(composition.ports().durableStore().orElse(null)!=store ||
                composition.ports().factBus().orElse(null)!=store ||
                composition.ports().worldEffects().isPresent() ||
                composition.ports().admission().isPresent() ||
                composition.missingPorts().size()!=9)
                throw new IllegalStateException("J04 provider composition unsafe");
            missingPorts=9;
            phase=Phase.RECOVERED_DISABLED;
        } catch(RuntimeException rejected) {
            // Preserve the actual WAL recovery failure for diagnostics; no fail-open.
            System.out.println("DIRECTOR_NATIVE_J04_STORE_FAILED_SAFE "
                +rejected.getClass().getName()+": "+rejected.getMessage()
                +(store==null?"":(" storeDiagnostic="+store.diagnostic())));
            phase=Phase.FAILED_SAFE;
            if(store!=null) {
                pendingClose=store.closeAsync();
                store=null;
            }
        }
    }
    public View view() {
        actor();
        // A writer can become read-only on its IO thread between actor polls.
        // No J01/J03/J07 consumer may see a positive durability flag meanwhile.
        boolean live=phase==Phase.RECOVERED_DISABLED && store!=null && !store.readOnlySafe();
        Phase observed=phase==Phase.RECOVERED_DISABLED && !live ? Phase.FAILED_SAFE : phase;
        return new View(observed,live,false,live?missingPorts:11);
    }
    /** This is the genuine recovered J04 boot fence, not a local guess. */
    public java.util.Optional<Epoch> recoveredBootEpoch() {
        actor();
        return phase==Phase.RECOVERED_DISABLED && store!=null && !store.readOnlySafe()
            ?java.util.Optional.of(store.session().bootEpoch())
            :java.util.Optional.empty();
    }
    /** Return ONLY a TEST_ONLY read projection after genuine J04 recovery. */
    public java.util.Optional<fr.proutlost.director.api.port.DurableStore>
            labReadOnlyMemoryStore() {
        labActor();
        if(phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            return java.util.Optional.empty();
        return java.util.Optional.of(new DirectorLabReadOnlyStore(store));
    }
    /** Explicit laboratory-only J01 WAL producer capability, never a port/provider. */
    public DirectorLabObservationWalIngestion labObservationIngestion(
            DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory) {
        labActor();
        if(phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            throw new IllegalStateException("J04 WAL proof required before lab ingestion");
        return new DirectorLabObservationWalIngestion(owner.server(),identity.worldId(),
            store,observation,memory);
    }
    /** TEST_ONLY real block-hook anchors -> J04 WAL -> immutable memory. */
    public DirectorNativeAnchorWalIngestion labAnchorIngestion(
            DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory) {
        labActor();
        if(phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            throw new IllegalStateException("recovered J04 required for anchor store");
        return new DirectorNativeAnchorWalIngestion(owner.server(),identity.worldId(),
            store,observation,memory);
    }
    /** Lab-only durable WORLD boot; never an automatic event admission port. */
    public DirectorNativeWorldStateBootstrap labWorldBootstrap(ConfigView testConfig) {
        labActor();
        if(phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            throw new IllegalStateException("recovered J04 required for WORLD boot");
        return new DirectorNativeWorldStateBootstrap(
            owner.server(),identity.worldId(),store,testConfig);
    }
    /** A disposable TEST_ONLY scheduler has no admission or effects port. */
    public DirectorNativeDecisionScheduler labDecisionScheduler(
            DirectorNativeObservationQualification observation,
            DirectorNativeMemoryProjectionQualification memory,
            DirectorNativeWorldStateBootstrap worldState) {
        labActor();
        if(phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            throw new IllegalStateException("J04 recovery required");
        return new DirectorNativeDecisionScheduler(owner.server(),identity.worldId(),
            store,observation,memory,worldState);
    }
    /** One coordinator uses the already recovered writer, only under the explicit LAB lifecycle. */
    DirectorNativeGameplay labGameplay() {
        labActor();
        if(!Boolean.getBoolean("proutlost.director.nativeLab") ||
           phase!=Phase.RECOVERED_DISABLED || store==null || store.readOnlySafe())
            throw new IllegalStateException("explicit recovered LAB required");
        return new DirectorNativeGameplay(owner.server(),identity.worldId(),store);
    }
    /** Call only after admission is closed and effect writers are stabilized.
     * Checkpoint is queued behind all accepted writes. Always release the writer
     * even if checkpoint fails, while preserving that failure for the caller. */
    private static CompletionStage<Void> checkpointAndClose(JournalStore closingStore) {
        var result=new CompletableFuture<Void>();
        CompletionStage<Long> checkpoint;
        try { checkpoint=closingStore.checkpoint(); }
        catch(RuntimeException failure) { checkpoint=CompletableFuture.failedFuture(failure); }
        checkpoint.whenComplete((generation,checkpointFailure)->{
            Throwable failure=checkpointFailure;
            if(failure==null && (generation==null || generation<1))
                failure=new IllegalStateException("Director shutdown checkpoint unavailable");
            final Throwable checkpointError=failure;
            try {
                closingStore.closeAsync().whenComplete((ignored,closeFailure)->{
                    if(checkpointError!=null) {
                        if(closeFailure!=null && closeFailure!=checkpointError)
                            checkpointError.addSuppressed(closeFailure);
                        result.completeExceptionally(checkpointError);
                    } else if(closeFailure!=null)result.completeExceptionally(closeFailure);
                    else result.complete(null);
                });
            } catch(RuntimeException closeFailure) {
                if(checkpointError!=null) {
                    checkpointError.addSuppressed(closeFailure);
                    result.completeExceptionally(checkpointError);
                } else result.completeExceptionally(closeFailure);
            }
        });
        return result.minimalCompletionStage();
    }
    /** Shutdown initiates ACK drain/checkpoint/close asynchronously; no actor join. */
    public CompletionStage<Void> closeAsync() {
        actor();
        if(shutdownRequested)return pendingClose;
        shutdownRequested=true;
        if(phase==Phase.CLOSED) {
            pendingClose=CompletableFuture.completedFuture(null);
            return pendingClose;
        }
        if(phase==Phase.CLOSING)return pendingClose;
        if(phase==Phase.NEW) {
            phase=Phase.CLOSED;
            pendingClose=CompletableFuture.completedFuture(null);
            return pendingClose;
        }
        if(phase==Phase.OPENING) {
            phase=Phase.CLOSING;
            pendingClose=pendingOpen.thenCompose(DirectorNativeStoreBootstrap::checkpointAndClose);
        } else if(store!=null) {
            phase=Phase.CLOSING;
            pendingClose=checkpointAndClose(store);
        } else {
            phase=Phase.CLOSING;
            if(pendingClose==null)pendingClose=CompletableFuture.completedFuture(null);
        }
        pendingClose=pendingClose.whenComplete((ignored,failure)->{
            // Callback belongs to writer/close thread: no world access here.
        });
        return pendingClose;
    }
    public void finishClose() {
        actor();
        if(phase!=Phase.CLOSING || pendingClose==null || !pendingClose.toCompletableFuture().isDone())
            return;
        if(pendingClose.toCompletableFuture().isCompletedExceptionally()) {
            phase=Phase.FAILED_SAFE;
            return;
        }
        store=null;
        pendingOpen=null;
        phase=Phase.CLOSED;
        missingPorts=11;
    }
}
