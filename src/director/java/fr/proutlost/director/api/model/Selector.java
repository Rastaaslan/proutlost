package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface Selector permits AnyOfEvents, AllTags {}
