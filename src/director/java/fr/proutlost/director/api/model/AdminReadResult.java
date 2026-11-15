package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record AdminReadResult(AdminStatus status, List<AdminRow> data, Optional<SystemError> error, Optional<String> nextCursor, Optional<AdminSnapshotStamp> snapshot) {
 /** Legacy construction carries unavailable metadata; never derive it from first row. */
 public AdminReadResult(AdminStatus status,List<AdminRow> data,Optional<SystemError> error,Optional<String> nextCursor){this(status,data,error,nextCursor,Optional.empty());}
 public AdminReadResult { Objects.requireNonNull(snapshot); require(data.size()<=50,"admin page bound"); Objects.requireNonNull(status); data=list(data); Objects.requireNonNull(error); Objects.requireNonNull(nextCursor);  }
}
