package fr.proutlost.contract.director;

import java.util.*;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Owned non-block presentation only. Idempotent token start/reconcile/stop; never physical mutation. */
public interface EffectAdapter {
    AdapterHealth validate(AdapterCapability capability);
    EffectReceipt start(EffectIntent intent);
    EffectReceipt reconcile(EffectToken token);
    EffectReceipt stop(EffectToken token);
}
