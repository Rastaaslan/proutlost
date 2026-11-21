package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.List;
import java.util.function.Supplier;
/** Optional actor-only diagnostics from the actual runner's arbiter. The
 * callback returns immutable data and never grants or renews a reservation. */
public interface ReservationInspectionPort {
 void inspectReservations(EventInstanceId event,Supplier<List<ReservationToken>> snapshot);
}
