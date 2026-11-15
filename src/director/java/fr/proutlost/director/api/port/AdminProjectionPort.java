package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
/** J05/bootstrap publishes immutable coherent actor snapshots; J07 reads bounded pages.
 * SUCCESS must carry AdminReadResult.snapshot even for an empty page; unavailable publication
 * returns DEFERRED, never guessed revision/tick/sequence. Expected revision mismatch defers.
 * No world access/IO and no hidden mutation on query. J07 remains the authorization facade.
 */
public interface AdminProjectionPort { AdminReadResult query(AdminQuery query); }
