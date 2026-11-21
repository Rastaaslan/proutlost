package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum RequestStatus { ACCEPTED, WAITING_CONTEXT, WAITING_SAFETY, SCHEDULED, EXECUTING, FULFILLED, CONFLICT, CANCELLED, EXPIRED }
