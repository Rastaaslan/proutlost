package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum ChainState { WAITING, READY, ACTIVE, SUSPENDED, BRANCHED, SETTLED_AS_WORLD_STATE, COMPLETED, ABORTED_SAFE }
