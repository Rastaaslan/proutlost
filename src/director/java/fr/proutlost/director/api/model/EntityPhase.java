package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum EntityPhase { RESERVED, SPAWN_INTENT_DURABLE, PRESENT, MOVING, SETTLED, CLEANING, REMOVED, ADOPTED, UNLOADED }
