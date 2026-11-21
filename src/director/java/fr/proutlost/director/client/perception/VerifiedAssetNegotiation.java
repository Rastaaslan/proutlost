package fr.proutlost.director.client.perception;

import fr.proutlost.director.api.model.*;
import java.util.*;

/**
 * Client-side J06 capability handshake, computed from local, verified asset
 * bytes and the exact server offer. Never treat a claimed type or an absent
 * asset as playable. Server claims are not resource verification.
 *
 * This is a pure DTO selection; it grants no PerceptionIntent or execution
 * authority. The renderer must still check the live resource registry.
 */
public final class VerifiedAssetNegotiation {
    private VerifiedAssetNegotiation() {}

    public record Local(PerceptionKind kind, Fingerprint hash) {
        public Local { Objects.requireNonNull(kind);Objects.requireNonNull(hash); }
    }

    public static Capabilities reply(SessionReset offer, Map<ResourceId,Local> verified) {
        Objects.requireNonNull(offer);
        Objects.requireNonNull(verified);
        if(verified.size()>128)throw new IllegalArgumentException("local asset count");
        Map<ResourceId,Fingerprint> accepted=new HashMap<>();
        Set<PerceptionKind> supported=EnumSet.noneOf(PerceptionKind.class);
        for(var e:verified.entrySet()){
            var id=Objects.requireNonNull(e.getKey());
            var local=Objects.requireNonNull(e.getValue());
            if(offer.authorizedEffects().contains(local.kind()) &&
               local.hash().equals(offer.authorizedAssets().get(id))) {
                accepted.put(id,local.hash());
                supported.add(local.kind());
            }
        }
        return new Capabilities(1,offer.connectionEpoch(),supported,accepted);
    }
}
