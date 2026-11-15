package fr.proutlost.ambient;

import net.neoforged.bus.api.Event;
import net.minecraft.server.MinecraftServer;

/** Notification after the environmental response; all subscribers are optional. */
public final class CampRoutineEvent extends Event {
    public final MinecraftServer server;
    public final CampRoutineMemory.Replay replay;
    public CampRoutineEvent(MinecraftServer server,CampRoutineMemory.Replay replay){this.server=server;this.replay=replay;}
}
