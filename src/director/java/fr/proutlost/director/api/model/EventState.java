package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum EventState { PROPOSED, RESERVED, PREPARING, READY, RUNNING, RESOLVING, SUCCEEDED, PARTIAL, ABORTED_SAFE, FAILED_SAFE, CLEANING, RECOVERING }
