package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum SafeState { UNCHANGED, DEFERRED, CLEANING, ISOLATED, READ_ONLY_SAFE }
