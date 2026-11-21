package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;

/** Ancillary plan admission belongs to the existing J04 actor/lane, not the resolver. */
public interface PhysicalSitePinJournalPort {
    enum State { PENDING,DURABLE,REFUSED,FAILED_SAFE }
    State ensurePinned(PhysicalSiteOffer offer);
    boolean durable(PhysicalSitePin pin);
    /** Bind the exact already-durable plan to the existing owner's central J05 guard. */
    void bindForward(PhysicalSitePin pin,ExecutionPlan plan,PhysicalSiteResolverPort resolver);
    /** Existing lifecycle already proved terminal; only transient guard/cache retirement. */
    void retire(PhysicalSitePin pin);
}
