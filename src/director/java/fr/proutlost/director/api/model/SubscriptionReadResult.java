package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Reading never ACKs, advances a consumer cursor or grants world access, including async completion. */
public record SubscriptionReadResult(ReadStatus status,Optional<Subscription> page,Optional<SubscriptionReadStamp> stamp,List<SystemError> errors) {
 public SubscriptionReadResult {Objects.requireNonNull(status);Objects.requireNonNull(page);Objects.requireNonNull(stamp);errors=list(errors);require(errors.size()<=32,"read errors bound");require((status==ReadStatus.AVAILABLE)==page.isPresent(),"read status/payload");
 if(status==ReadStatus.AVAILABLE){require(stamp.isPresent()&&errors.isEmpty(),"available subscription stamp/errors");var v=page.orElseThrow();var s=stamp.orElseThrow();require(v.consumer().nextSequence().equals(s.expectedCursor()),"expected cursor mismatch");require(ReadContracts.queryHash(v.consumer()).equals(s.store().queryHash()),"consumer query mismatch");require(v.batch().size()<=v.consumer().batchLimit(),"subscription page bound");require(v.nextSequence().equals(s.scannedThrough().plus(1)),"scan continuation mismatch");require(v.hasMore()==(s.scannedThrough().compareTo(s.outboxHighWater())<0),"outbox high water mismatch");long previous=-1;var ids=new HashSet<MessageId>();
 for(var message:v.batch()){require(message.worldId().equals(s.store().session().worldId()),"message world mismatch");require(message.worldSequence().isPresent(),"durable message sequence required");long seq=message.worldSequence().orElseThrow().value();require(seq>=s.expectedCursor().value()&&seq<=s.scannedThrough().value()&&seq>previous,"message scan order/range");previous=seq;require(ids.add(message.messageId()),"duplicate message");require(ReadContracts.matches(v.consumer(),message),"message filter mismatch");}
 }
 }
}
