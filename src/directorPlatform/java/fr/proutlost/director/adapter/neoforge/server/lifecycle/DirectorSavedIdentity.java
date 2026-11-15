package fr.proutlost.director.adapter.neoforge.server.lifecycle;

import fr.proutlost.director.api.model.WorldId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-local NBT projection of Director identity and last verified WAL anchor.
 * Not an authority for event admission or transactions. The WAL manifest must
 * be reconciled independently before production bindings are published.
 */
public final class DirectorSavedIdentity extends SavedData {
    public static final String KEY="proutlost_director_identity";
    public static final int SCHEMA=1;
    public static final Factory<DirectorSavedIdentity> FACTORY=
        new Factory<>(DirectorSavedIdentity::fresh,DirectorSavedIdentity::load);

    private final WorldId world;
    private Optional<UUID> storeId;
    private long storeGeneration;
    private long lastCheckpoint;

    private DirectorSavedIdentity(WorldId world,Optional<UUID> storeId,
            long generation,long checkpoint,boolean fresh) {
        this.world=Objects.requireNonNull(world);
        this.storeId=Objects.requireNonNull(storeId);
        if(generation<0 || checkpoint<0 ||
            (storeId.isEmpty() && (generation!=0 || checkpoint!=0)) ||
            (storeId.isPresent() && generation==0))
            throw new IllegalArgumentException("inconsistent Director identity anchor");
        this.storeGeneration=generation;
        this.lastCheckpoint=checkpoint;
        if(fresh)setDirty();
    }

    private static DirectorSavedIdentity fresh() {
        return new DirectorSavedIdentity(new WorldId(UUID.randomUUID()),Optional.empty(),0,0,true);
    }

    public static DirectorSavedIdentity load(CompoundTag source,HolderLookup.Provider registry) {
        Objects.requireNonNull(source);
        if(!source.contains("schema") || source.getInt("schema")!=SCHEMA ||
            !source.hasUUID("world_id"))
            throw new IllegalStateException("Unknown or corrupt Director identity schema");
        Optional<UUID> store;
        if(source.contains("store_id")) {
            if(!source.hasUUID("store_id"))throw new IllegalStateException("Invalid Director store identity");
            store=Optional.of(source.getUUID("store_id"));
        } else store=Optional.empty();
        if(!source.contains("generation") || !source.contains("checkpoint"))
            throw new IllegalStateException("Missing Director identity anchor bounds");
        return new DirectorSavedIdentity(new WorldId(source.getUUID("world_id")),store,
            source.getLong("generation"),source.getLong("checkpoint"),false);
    }

    public WorldId worldId(){return world;}
    public Optional<UUID> storeId(){return storeId;}
    public long storeGeneration(){return storeGeneration;}
    public long lastCheckpoint(){return lastCheckpoint;}

    /** Only a verified WAL manifest may update this projection; never a fake read. */
    public void recordVerifiedStore(UUID store,long generation,long checkpoint) {
        Objects.requireNonNull(store);
        if(generation<1 || checkpoint<0 ||
            storeId.isPresent() && !storeId.orElseThrow().equals(store) ||
            generation<storeGeneration || checkpoint<lastCheckpoint)
            throw new IllegalArgumentException("Director WAL anchor regression/mismatch");
        if(storeId.isPresent() && storeGeneration==generation && lastCheckpoint==checkpoint)
            return;
        storeId=Optional.of(store);
        storeGeneration=generation;
        lastCheckpoint=checkpoint;
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag target,HolderLookup.Provider registry) {
        target.putInt("schema",SCHEMA);
        target.putUUID("world_id",world.value());
        storeId.ifPresent(store->target.putUUID("store_id",store));
        target.putLong("generation",storeGeneration);
        target.putLong("checkpoint",lastCheckpoint);
        return target;
    }
}
