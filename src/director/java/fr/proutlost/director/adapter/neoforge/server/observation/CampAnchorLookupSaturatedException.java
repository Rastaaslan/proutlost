package fr.proutlost.director.adapter.neoforge.server.observation;

/**
 * A verified anchor lookup exceeded its bounded neighbourhood budget.
 * The observer must report missing camp evidence, never publish a partial
 * candidate list or crash the Minecraft server tick/event dispatcher.
 */
public final class CampAnchorLookupSaturatedException
        extends IllegalStateException {
    public CampAnchorLookupSaturatedException() {
        super("J01 camp neighbourhood saturated");
    }
}
