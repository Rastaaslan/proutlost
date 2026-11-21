package fr.proutlost.director.adapter.neoforge.server.effects;
import fr.proutlost.director.api.model.*;
import java.util.*;
/** Common result contract of the existing actor-owned anchor mutation lane. */
public interface AnchorMutationBinding extends ServerEffectPort.Binding {
 boolean wasApplied();
 AnchorRecord after();
 default List<DirectorFact> resultFacts(){return List.of();}
}
