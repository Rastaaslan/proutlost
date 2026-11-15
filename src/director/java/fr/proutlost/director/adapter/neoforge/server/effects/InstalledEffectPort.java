package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.api.model.PrimitiveId;
import fr.proutlost.director.api.port.WorldEffectPort;
import java.util.Map;
/** Inventory captured from installed, native, permit-consuming bindings. */
public interface InstalledEffectPort extends WorldEffectPort {
 Map<PrimitiveId,ServerEffectPort.BindingView> installedBindingDescriptors();
}
