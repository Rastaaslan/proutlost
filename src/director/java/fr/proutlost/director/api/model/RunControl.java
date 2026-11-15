package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum RunControl { BOOTSTRAP, RECOVERING, RUNNING, PAUSED, DRAINING, READ_ONLY_SAFE }
