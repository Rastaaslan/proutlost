package fr.proutlost.contract.director;

import java.util.List;

/** Pure input hashing only; D3 owns gates, debt calculation, ranking and reservation. */
public final class ArbitrationKeys {
    private ArbitrationKeys() {}
    public static Digest tieBreak(RoundInput round, Id need, Revision definition, AudienceScope audience, LocationRef location) {
        return Digests.sha256(StrictCodec.encode(List.of(round.world_id(), round.round_id(), need, definition,
            audience.scope_id(), audience.membership_epoch(), location.location_id(), round.seed(), round.draw_index())));
    }
}
