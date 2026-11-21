package fr.proutlost.director.api.model;
import java.util.*;
/** Current non-evicting receipt-index read. An empty receipt is proven absent
 * at this published cut, not proof that an append is no longer in flight. */
public record StoreTransactionReadback(StoreSession session,Sequence cut,Optional<CommitAck> receipt){
    public StoreTransactionReadback {Objects.requireNonNull(session);Objects.requireNonNull(cut);Objects.requireNonNull(receipt);}
}
