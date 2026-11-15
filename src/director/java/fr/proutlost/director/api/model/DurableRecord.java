package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface DurableRecord permits DurablePrepare, DurableFact, DurableMutation, DurableOwnership, DurableRequest, DurableAudit, DurableOutbox, DurableUnique, DurableAggregate, DurableInbox, DurableResult, DurableMemoryDelta, DurableRuntimeAdminAudit {}
