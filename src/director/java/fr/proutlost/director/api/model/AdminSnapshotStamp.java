package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
public record AdminSnapshotStamp(WorldId worldId,Epoch bootEpoch,Revision snapshotRevision,Tick capturedTick,Sequence lastDurableSequence) {public AdminSnapshotStamp {Objects.requireNonNull(worldId);Objects.requireNonNull(bootEpoch);Objects.requireNonNull(snapshotRevision);Objects.requireNonNull(capturedTick);Objects.requireNonNull(lastDurableSequence);}}
