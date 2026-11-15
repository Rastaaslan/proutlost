package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum ClaimPolicy { EXCLUSIVE, SHARE, MERGE, DEFER, PREEMPT_BEFORE_EFFECT }
