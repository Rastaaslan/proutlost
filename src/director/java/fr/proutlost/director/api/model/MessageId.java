package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record MessageId(UUID value) implements Comparable<MessageId> {
 public MessageId { Objects.requireNonNull(value); }
 public static MessageId parse(String value) { UUID id=UUID.fromString(value); require(id.toString().equals(value), "canonical UUID"); return new MessageId(id); }
 public int compareTo(MessageId other) { return value.toString().compareTo(other.value.toString()); }
}
