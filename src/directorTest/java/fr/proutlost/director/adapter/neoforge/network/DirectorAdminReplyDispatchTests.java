package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure test seam for actual J07 native reply sender; NOT a two-client GameTest. */
final class DirectorAdminReplyDispatchTests {
    private DirectorAdminReplyDispatchTests() {}

    static void run() {
        var id=new PlayerId(new UUID(82,83));
        var transport=new PerceptionTransport(NetworkTests.WORLD,new Epoch(1),
            Set.of(),Map.of(),
            new PerceptionTransport.Limits(3,4,4,1200,8,2,40),
            ()->100L,(p,message)->{});
        var first=transport.open(id,NetworkTests.DIM);
        NetworkTests.check(transport.capabilities(id,new Capabilities(1,
            first.connectionEpoch(),Set.of(),Map.of())),
            "F8 requires authenticated even if visual assets are empty");
        var initialKey=PerceptionTransport.adminSession(first);
        var attempted=new AtomicInteger();
        var invalidated=new AtomicInteger();
        NetworkTests.check(DirectorAdminReplyDispatch.send(
            attempted::incrementAndGet,invalidated::incrementAndGet),
            "valid F8 reply must be accepted");
        NetworkTests.check(attempted.get()==1 && invalidated.get()==0,
            "successful reply must not invalidate session");
        NetworkTests.check(transport.admin(id,initialKey),
            "successful response keeps fresh F8 authority");

        boolean sent=DirectorAdminReplyDispatch.send(
            ()->{throw new IllegalStateException("packet queued then socket failed");},
            ()->{invalidated.incrementAndGet();transport.close(id);});
        NetworkTests.check(!sent && invalidated.get()==1,
            "uncertain J07 send must quarantine exactly once");
        NetworkTests.check(transport.identity(id).isEmpty(),
            "uncertain F8 reply retains no live J06 identity");
        NetworkTests.check(!transport.admin(id,initialKey),
            "quarantine must refuse previously valid F8 session");
        var second=transport.open(id,NetworkTests.DIM);
        NetworkTests.check(second.connectionEpoch().compareTo(first.connectionEpoch())>0,
            "retry must negotiate new J06 connection epoch");
        NetworkTests.check(!transport.admin(id,initialKey)
                && !transport.admin(id,PerceptionTransport.adminSession(second)),
            "reconnected F8 must be blocked before new J06 handshake");
        NetworkTests.check(transport.capabilities(id,new Capabilities(1,
            second.connectionEpoch(),Set.of(),Map.of())),
            "reconnected client negotiated J06");
        NetworkTests.check(!transport.admin(id,initialKey)
                && transport.admin(id,PerceptionTransport.adminSession(second)),
            "reconnect must reject old key and accept only new F8 key");

        try {
            DirectorAdminReplyDispatch.send(
                ()->{throw new IllegalStateException("socket failed");},
                ()->{throw new IllegalArgumentException("revoke failed");});
            throw new AssertionError("missing emergency revoke propagation");
        } catch(IllegalStateException expected) {
            NetworkTests.check(expected.getCause()!=null
                && expected.getCause().getSuppressed().length==1,
                "emergency caller must detect failed per-player revocation");
        }
    }
}
