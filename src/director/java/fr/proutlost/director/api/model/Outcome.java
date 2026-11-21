package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum Outcome { SUCCEEDED, PARTIAL, ABORTED_SAFE, FAILED_SAFE, DELIVERY_UNCERTAIN, RECONCILED }
