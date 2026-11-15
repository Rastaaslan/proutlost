package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record UnitValue(double value) { public UnitValue { finite(value); require(value>=0 && value<=1,"unit interval"); } }
