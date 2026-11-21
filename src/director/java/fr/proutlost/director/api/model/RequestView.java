package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record RequestView(DirectorRequest request, DirectorRequestState state, Revision revision) {
 public RequestView { Objects.requireNonNull(request); Objects.requireNonNull(state); Objects.requireNonNull(revision); require(request.producerId().equals(state.producerId())&&request.producerKey().equals(state.producerKey())&&request.scope().equals(state.scope())&&revision.equals(state.header().revision()),"request/state binding"); require(RequestContracts.id(request.payload()).equals(state.requestId()),"request identity"); }
}
