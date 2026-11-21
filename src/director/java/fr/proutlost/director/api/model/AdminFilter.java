package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface AdminFilter permits WorldFilter, EventFilter, MutationFilter, RegionFilter, PlayerFilter, ChainFilter, DecisionFilter, LaboratoryFilter {}
