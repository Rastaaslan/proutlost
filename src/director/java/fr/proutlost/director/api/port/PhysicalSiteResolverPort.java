package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Optional full-D0 planner adapter. No filesystem, waits or world writes on these actor calls. */
public interface PhysicalSiteResolverPort {
    /** Requirements are explicitly authored and definition-hash pinned, never inferred from tags. */
    boolean requires(DefinitionRef definition);
    Optional<PhysicalSiteSelection> resolve(Candidate candidate,DecisionInput input,
        EventInstanceId event,List<PreparedEffect> actualPreview);
    default PhysicalSiteOffer pin(PhysicalSiteSelection selection,ExecutionPlan plan){return selection.pin(plan);}
    void discard(PhysicalSiteSelection unselected);
    /** Only actual J04-read blobs may restore a historical ancillary pin. */
    void restore(PhysicalSiteOffer offer);
    /** Recheck current authority/view/observation/policies and exact original plan before forward work. */
    void requireCurrent(PhysicalSitePin pin,ExecutionPlan plan,Optional<EffectIntent> effect);
    /** Release only transient planning state after its existing event lifecycle is terminal. */
    void retire(PhysicalSitePin pin);
}
