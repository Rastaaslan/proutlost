package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** AVAILABLE empty page proves absence only in the verified page interval, never on a cache miss. */
public record StoreReadResult(ReadStatus status,Optional<StoreView> page,Optional<ReadStamp> stamp,List<SystemError> errors) {
 public StoreReadResult {Objects.requireNonNull(status);Objects.requireNonNull(page);Objects.requireNonNull(stamp);errors=list(errors);require(errors.size()<=32,"read errors bound");require((status==ReadStatus.AVAILABLE)==page.isPresent(),"read status/payload");
 if(status==ReadStatus.AVAILABLE){require(stamp.isPresent()&&errors.isEmpty(),"available read stamp/errors");var v=page.orElseThrow();var s=stamp.orElseThrow();require(v.lastDurableSequence().equals(s.snapshotSequence()),"snapshot cut mismatch");require((long)v.records().size()+v.aggregates().size()<=1024,"read page bound");for(var a:v.aggregates())require(a.committedSequence().compareTo(s.snapshotSequence())<=0,"aggregate beyond snapshot");require(v.hasMore()==v.nextCursor().isPresent(),"continuation required iff more");v.nextCursor().ifPresent(c->require(ReadContracts.decodeCursor(c).stamp().equals(s),"continuation stamp mismatch"));}
 }
}
