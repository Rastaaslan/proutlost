package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Immutable G0 contract; carries data, never execution authority. */
public record BlobReadResult(AckStatus status, List<BlobContent> contents, List<SystemError> errors) {
 public BlobReadResult { Objects.requireNonNull(status); contents=list(contents); errors=list(errors); require(contents.size()<=256,"blob read bound"); require(status!=AckStatus.DURABLE||errors.isEmpty(),"blob read errors"); }
}
