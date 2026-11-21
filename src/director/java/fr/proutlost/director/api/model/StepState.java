package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum StepState { WAITING, PREPARED, APPLIED, CONFIRMED, SKIPPED_SAFE, CONFLICT, FAILED }
