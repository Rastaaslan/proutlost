package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.admin.client.ClientAdminReplyFence;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Pure J07 regression for the actual F8 inbound reply gate. */
public final class ClientAdminReplyFenceTests {
    private ClientAdminReplyFenceTests() {}
    private static RequestId id(long n){return new RequestId(new UUID(17,n));}
    private static SessionIdentity identity(long conn){
        return new SessionIdentity(NetworkTests.WORLD,new Epoch(1),new Epoch(conn),
            NetworkTests.DIM,new PlayerId(new UUID(99,1)));
    }
    private static AdminQuery query(SessionIdentity session,long n){
        return new AdminQuery(DirectorAdminSessionKey.of(session),id(n),
            AdminPanel.WORLD,new WorldFilter(),Optional.empty(),Optional.empty(),20);
    }
    private static AdminSnapshot snapshot(long n){
        return new AdminSnapshot(id(n),new Revision(1),new Tick(1),
            new Sequence(1),List.of(),Optional.empty(),Set.of());
    }
    private static AdminCommandResult result(long n,AdminStatus status){
        return new AdminCommandResult(id(n),status,List.of(),Optional.empty(),Optional.empty());
    }
    private static void refused(Runnable runnable,String label) {
        boolean caught=false;
        try {runnable.run();}catch(IllegalStateException expected){caught=true;}
        NetworkTests.check(caught,label);
    }
    public static void run(){
        var fence=new ClientAdminReplyFence(2);
        var a=identity(1);
        var b=identity(2);
        refused(()->fence.track(query(a,1)),"no negotiated identity refused");
        fence.reset(a);
        fence.track(query(a,1));
        NetworkTests.check(!fence.accept(snapshot(2)),"unknown reply accepted");
        NetworkTests.check(fence.accept(snapshot(1)),"current query refused");
        NetworkTests.check(!fence.accept(snapshot(1)),"replayed query accepted");
        refused(()->fence.track(query(b,2)),"foreign connection request accepted");
        fence.track(query(a,3));
        fence.track(query(a,4));
        refused(()->fence.track(query(a,5)),"unbounded in-flight queries");
        NetworkTests.check(fence.pendingCount()==2,"capacity fence altered state");
        fence.reset(b);
        NetworkTests.check(!fence.accept(snapshot(3)) && fence.pendingCount()==0,
            "old J06 connection reply survived reset");
        var command=new AdminCommand(DirectorAdminSessionKey.of(b),id(6),
            new Revision(1),AdminOperation.PAUSE,new ControlPayload(RunControl.PAUSED));
        fence.track(command);
        NetworkTests.check(fence.accept(result(6,AdminStatus.PENDING)) &&
            fence.pendingCount()==1,"pending result lost correlation");
        NetworkTests.check(fence.accept(result(6,AdminStatus.REJECTED)) &&
            fence.pendingCount()==0,"terminal command result not retired");
        NetworkTests.check(!fence.accept(result(6,AdminStatus.SUCCEEDED)),
            "duplicated terminal result accepted");
        fence.track(query(b,7));
        fence.cancel(id(7));
        NetworkTests.check(!fence.accept(snapshot(7)),"failed C2S send reply accepted");
        fence.track(query(b,8));
        fence.revoke();
        NetworkTests.check(!fence.accept(snapshot(8)) && fence.pendingCount()==0,
            "logged-out F8 accepted delayed reply");
    }
}
