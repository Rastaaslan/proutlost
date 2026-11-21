package fr.proutlost.director.integration.locations;

import fr.proutlost.director.api.codec.MetadataPreparationClockContract;
import fr.proutlost.director.api.model.*;
import java.util.*;

/** Public native compatibility facade; shared closed facts stay in the API.
 * A private proposal/spool never supplies authority to the shared clock.
 */
public final class MetadataPreparationClock {
    public static final FactType TYPE=MetadataPreparationClockContract.TYPE;
    public record Prepared(LocationTransferManifest transfer,Tick tick) {}
    private MetadataPreparationClock(){}
    public static Tick proposalTick(Optional<Tick> durable,Optional<Tick> local,Tick now){return MetadataPreparationClockContract.proposalTick(durable,local,now);}
    public static DirectorFact fact(LocationTransferManifest transfer,Tick tick){return MetadataPreparationClockContract.fact(transfer,tick);}
    public static Optional<Prepared> decode(DirectorFact fact,WorldId world){
        return MetadataPreparationClockContract.decode(fact,world).map(value->new Prepared(value.transfer(),value.tick()));
    }
}
