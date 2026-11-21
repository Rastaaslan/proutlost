package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum OwnershipDisposition { DIRECTOR_ACTIVE, DIRECTOR_DURABLE, SHARED_EXPLICIT, YIELDED_TO_PLAYER, RELEASED, CONFLICT_ISOLATED }
