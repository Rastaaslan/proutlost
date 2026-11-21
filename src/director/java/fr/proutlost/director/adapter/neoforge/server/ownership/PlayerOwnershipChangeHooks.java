package fr.proutlost.director.adapter.neoforge.server.ownership;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Real NeoForge player block change observation, actor-only.
 *
 * Only installed explicitly by the integration owner once J04 storage and
 * reversion-safe ownership persistence are available. It is NOT installed by
 * the unqualified lab bootstrap. A local attachment is marked immediately on
 * the server actor; the subscriber is responsible for durable WAL recording.
 *
 * Events are observed at LOWEST, excluding already cancelled edits. A later
 * edit cancellation is conservatively treated as a relinquishment (not an
 * unauthorized mutation). An absent attachment is never created by this hook.
 */
public final class PlayerOwnershipChangeHooks implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerOwnershipAttachments ownership;
    private final Supplier<Tick> now;
    private final Consumer<OwnershipRecord> enqueueDurable;
    private final IEventBus bus;
    private final java.util.function.Consumer<BlockEvent.BreakEvent> breakListener=this::broken;
    private final java.util.function.Consumer<BlockEvent.EntityPlaceEvent> placeListener=this::placed;
    private boolean closed;

    public PlayerOwnershipChangeHooks(MinecraftServer server,
            ServerOwnershipAttachments ownership,Supplier<Tick> now,
            Consumer<OwnershipRecord> enqueueDurable,IEventBus bus) {
        this.server=Objects.requireNonNull(server);
        this.ownership=Objects.requireNonNull(ownership);
        this.now=Objects.requireNonNull(now);
        this.enqueueDurable=Objects.requireNonNull(enqueueDurable);
        this.bus=Objects.requireNonNull(bus);
        if(!server.isSameThread())
            throw new IllegalStateException("Minecraft actor required for hook registration");
        bus.addListener(EventPriority.LOWEST,breakListener);
        bus.addListener(EventPriority.LOWEST,placeListener);
    }
    private void actor() {
        if(closed || !server.isSameThread())
            throw new IllegalStateException("ownership hook not active on server actor");
    }
    private void changed(ServerLevel level,BlockPos pos) {
        actor();
        if(level.getServer()!=server || level.getChunkSource()
                .getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)
            return; // Never force-load chunks.
        ownership.invalidate(level,pos,true,now.get())
            .ifPresent(enqueueDurable);
    }
    private void broken(BlockEvent.BreakEvent event) {
        if(event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) ||
            !(event.getPlayer() instanceof ServerPlayer player) ||
            player.getServer()!=server)
            return;
        changed(level,event.getPos());
    }
    private void placed(BlockEvent.EntityPlaceEvent event) {
        if(event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) ||
            !(event.getEntity() instanceof ServerPlayer player) ||
            player.getServer()!=server)
            return;
        changed(level,event.getPos());
    }
    @Override public void close() {
        actor();
        bus.unregister(breakListener);
        bus.unregister(placeListener);
        closed=true;
    }
}
