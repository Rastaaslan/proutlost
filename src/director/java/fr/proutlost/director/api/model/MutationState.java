package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum MutationState { PLANNED, PREPARED_DURABLE, APPLIED_UNCONFIRMED, VERIFIED, COMMITTED, COMPENSATING, ROLLED_BACK, YIELDED, CONFLICT_ISOLATED }
