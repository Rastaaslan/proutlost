package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface QuestDirectorPort { CompletionStage<RequestAck> submit(DirectorRequest request); /** Authorized internal adapter path; persist policy, never infer it from content conflicts. */ CompletionStage<RequestAck> submit(DirectorRequest request, RequestSchedulingPolicy policy); RequestView inspect(RequestId id); CompletionStage<RequestAck> cancel(CancelRequest request); }
