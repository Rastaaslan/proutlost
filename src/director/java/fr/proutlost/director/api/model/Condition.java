package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface Condition permits AllConditions, AnyConditions, NotCondition, PlayersPresent, RegionTag, PatternConfidence, SoloReady, RiskMinimum, RequirementPending, MemoryFlag {}
