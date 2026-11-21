package fr.proutlost.director.testkit;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.*;
import fr.proutlost.director.api.codec.ModelCodec;
import java.util.*;
/** TEST_ONLY bounded mailbox fixture. Does not execute control commands or claim disk durability. */
public final class TestRuntimeAdminPort implements RuntimeAdminPort {
 private final ActorAuthorityPort authority; private final int capacity;
 private final Map<RuntimeAdminKey,AdminCommand> commands=new LinkedHashMap<>();
 private final Map<RuntimeAdminKey,AdminResult> results=new LinkedHashMap<>();
 private final List<DurableRuntimeAdminAudit> audit=new ArrayList<>();
 private final AdminSnapshotStamp stamp;
 public TestRuntimeAdminPort(ActorAuthorityPort authority,int capacity,AdminSnapshotStamp stamp){if(capacity<1||capacity>64)throw new IllegalArgumentException();this.authority=authority;this.capacity=capacity;this.stamp=stamp;}
 private AdminResult result(AdminStatus status,RequestId id){return new AdminResult(status,id,stamp.snapshotRevision(),Optional.empty(),Optional.empty());}
 public AdminResult submit(AdminCommand c){var key=new RuntimeAdminKey(c.actorSession(),c.requestId());if(commands.containsKey(key))return commands.get(key).equals(c)?results.get(key):result(AdminStatus.REJECTED,c.requestId());if(commands.size()==capacity)return result(AdminStatus.DEFERRED,c.requestId());if(authority.current(c.actorSession()).filter(a->AdminContracts.authorized(c,a)).isEmpty())return result(AdminStatus.REJECTED,c.requestId());commands.put(key,c);var pending=result(AdminStatus.PENDING,c.requestId());results.put(key,pending);return pending;}
 public AdminResult poll(RuntimeAdminKey key){return results.getOrDefault(key,result(AdminStatus.DEFERRED,key.requestId()));}
 public void apply(RuntimeAdminKey key){var c=commands.get(key);if(c==null||results.get(key).status()!=AdminStatus.PENDING)return;var actor=authority.current(key.actorSession());if(actor.isEmpty()||!AdminContracts.authorized(c,actor.get())||!c.expectedRevision().equals(stamp.snapshotRevision())){results.put(key,result(AdminStatus.REJECTED,key.requestId()));return;}var terminal=result(AdminStatus.SUCCEEDED,c.requestId());audit.add(new DurableRuntimeAdminAudit(new RuntimeAdminAudit(c,BlobContent.hash(new ModelCodec<>(AdminCommand.class).encode(c)),actor.get(),stamp,stamp,terminal)));results.put(key,terminal);}
 public List<DurableRuntimeAdminAudit> audit(){return List.copyOf(audit);}
}
