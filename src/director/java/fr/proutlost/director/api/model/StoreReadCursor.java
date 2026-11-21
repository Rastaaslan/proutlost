package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Canonical bounded continuation data. J04 verifies retained snapshot AND position; not a signature. */
public record StoreReadCursor(ReadStamp stamp,String position) {
 public StoreReadCursor {Objects.requireNonNull(stamp);text(position);require(!position.isEmpty()&&position.length()<=512,"cursor position bound");}
}
