package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import java.util.concurrent.CompletionStage;
/** Optional adapter contract; no production implementation in G0. */
public interface AiTransport { CompletionStage<AiTransportResponse> propose(AiTransportRequest request); }
