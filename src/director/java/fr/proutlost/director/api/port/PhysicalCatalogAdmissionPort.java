package fr.proutlost.director.api.port;

import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.model.PhysicalCatalogModels.*;
import java.util.concurrent.CompletionStage;

/** Optional actor-owned full D0 lane; DTOs do not grant execution authority. */
public interface PhysicalCatalogAdmissionPort {
    Progress submit(SessionId actorSession,Document document);
    Progress poll(SessionId actorSession,Key key);
    CompletionStage<BlobReadResult> readSource(SessionId actorSession,Readback readback);
}
