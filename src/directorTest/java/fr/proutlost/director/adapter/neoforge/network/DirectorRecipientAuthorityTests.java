package fr.proutlost.director.adapter.neoforge.network;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Native J06 recipient preconditions; no fake player is a real Minecraft client. */
final class DirectorRecipientAuthorityTests {
    private DirectorRecipientAuthorityTests() {}
    static void run() {
        var alice=new PlayerId(new UUID(81,82));
        var bob=new PlayerId(new UUID(83,84));
        var session=new SessionIdentity(NetworkTests.WORLD,
            new Epoch(13),new Epoch(5),NetworkTests.DIM,alice);
        var negotiated=new Capabilities(1,new Epoch(5),
            Set.of(PerceptionKind.SOUND),
            Map.of(NetworkTests.ASSET,NetworkTests.HASH));
        var anotherDimension=new DimensionId("minecraft:the_nether");
        NetworkTests.check(DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"negotiated real recipient ready");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(null,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"missing J06 identity");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            null,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"no client handshake");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,new WorldId(new UUID(99,90)),new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"foreign J04 world");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(14),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"reboot WAL epoch rotation");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),bob,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"role spoof by another player");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            anotherDimension,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"real dimension changed");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            new Capabilities(1,new Epoch(6),Set.of(PerceptionKind.SOUND),
                Map.of(NetworkTests.ASSET,NetworkTests.HASH)),
            NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"old connection epoch");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.PARTICLES,NetworkTests.ASSET,
            NetworkTests.HASH),"unnegotiated renderer kind");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,
            new ResourceId("proutlost:foreign_asset"),
            NetworkTests.HASH),"unapproved asset");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            negotiated,NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            new Fingerprint("b".repeat(64))),"asset fingerprint mismatch");
        NetworkTests.check(!DirectorRecipientAuthority.negotiated(session,
            new Capabilities(1,new Epoch(5),Set.of(),Map.of()),
            NetworkTests.WORLD,new Epoch(13),alice,
            NetworkTests.DIM,PerceptionKind.SOUND,NetworkTests.ASSET,
            NetworkTests.HASH),"empty TEST_ONLY asset registry cannot authorize");
    }
}
