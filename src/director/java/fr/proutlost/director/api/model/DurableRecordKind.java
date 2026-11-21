package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum DurableRecordKind { PREPARE, RESULT, MUTATION, OWNERSHIP, FACT, OUTBOX, INBOX, REQUEST, AUDIT, AGGREGATE, UNIQUE }
