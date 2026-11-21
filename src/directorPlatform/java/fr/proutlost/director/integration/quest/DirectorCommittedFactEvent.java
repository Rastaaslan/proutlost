package fr.proutlost.director.integration.quest;

import fr.proutlost.director.api.model.DirectorFact;
import fr.proutlost.director.api.model.EvidenceStatus;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** Passive, confirmed J04 fact notification. Never gives quest logic world-effect authority. */
public final class DirectorCommittedFactEvent extends Event {
    private final MinecraftServer server;
    private final DirectorFact fact;

    public DirectorCommittedFactEvent(MinecraftServer server, DirectorFact fact) {
        this.server = Objects.requireNonNull(server);
        this.fact = Objects.requireNonNull(fact);
        if (!fact.durable() || fact.evidenceStatus() != EvidenceStatus.CONFIRMED)
            throw new IllegalArgumentException("Only durable confirmed facts can be announced");
    }
    public MinecraftServer server() { return server; }
    public DirectorFact fact() { return fact; }
}
