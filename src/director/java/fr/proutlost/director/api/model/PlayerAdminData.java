package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record PlayerAdminData(PlayerOperationalMemory operational, List<PlayerSignificantMemory> significant) implements AdminData {
 public PlayerAdminData { Objects.requireNonNull(operational); significant=list(significant);  }
}
