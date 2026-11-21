package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum OnFailure { ABORT_SAFE, SKIP_SAFE, PARTIAL_AND_STABILIZE }
