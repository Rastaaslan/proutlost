package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Five-tab actions. Server capabilities gate new operations; the seven legacy
 * inspections remain available on an authenticated TEST_ONLY read snapshot. */
public final class AdminClientOperationMenu {
    private AdminClientOperationMenu() {}
    public static List<AdminOperation> forPanel(AdminPanel panel) {
        return switch(Objects.requireNonNull(panel)) {
            case WORLD -> List.of(AdminOperation.STATUS,AdminOperation.PAUSE,AdminOperation.RESUME,
                AdminOperation.DRAIN,AdminOperation.TENSION,AdminOperation.INTENSITY,AdminOperation.RESCUE,AdminOperation.REPORT);
            case ACTIVE -> List.of(AdminOperation.EVENT_LIST,AdminOperation.EVENT_INSPECT,
                AdminOperation.MUTATION_INSPECT,AdminOperation.CHAIN,AdminOperation.STOP,AdminOperation.STABILIZE);
            case MEMORY -> List.of(AdminOperation.REGION,AdminOperation.PLAYER);
            case DECISIONS -> List.of(AdminOperation.REPLAY,AdminOperation.AUDIT_EXPORT);
            case LABORATORY -> List.of(AdminOperation.SIMULATE,AdminOperation.TRIGGER);
        };
    }
    public static boolean available(AdminPanel panel,AdminOperation operation) {
        return forPanel(panel).contains(Objects.requireNonNull(operation))&&AdminClientReadPayloads.supported(operation);
    }
    public static boolean requiresCapability(AdminOperation operation) {
        return switch(Objects.requireNonNull(operation)) {
            case STATUS,EVENT_LIST,EVENT_INSPECT,MUTATION_INSPECT,REGION,PLAYER,CHAIN -> false;
            default -> true;
        };
    }
    /** Snapshot readiness is not authority. Server reauthenticates every request.
     * TEST_ONLY pages with empty capabilities never enable modifying/calculation actions. */
    public static boolean canSend(AdminPanel panel,AdminOperation operation,Optional<AdminSnapshot> snapshot) {
        Objects.requireNonNull(snapshot);
        return available(panel,operation)&&snapshot.isPresent()&&
            (!requiresCapability(operation)||snapshot.orElseThrow().capabilities().contains(operation));
    }
    public static boolean canSendReadOnly(AdminPanel panel,AdminOperation operation,Optional<AdminSnapshot> snapshot) {
        return canSend(panel,operation,snapshot);
    }
}
