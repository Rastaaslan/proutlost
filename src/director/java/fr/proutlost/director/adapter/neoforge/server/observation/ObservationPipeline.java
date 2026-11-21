package fr.proutlost.director.adapter.neoforge.server.observation;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Actor-local immutable delivery of ordinary and camp observations in one durable inbox batch. */
public final class ObservationPipeline {
    private final ObservationBuffer buffer;
    private final CampSampler camps;
    private final long campCadence;
    private ObservationBatch ordinary, delivery;
    private List<EvidenceWindowId> campIds=List.of();
    public ObservationPipeline(ObservationBuffer buffer,CampSampler camps){this(buffer,camps,0);}
    public ObservationPipeline(ObservationBuffer buffer,CampSampler camps,long campCadence){
        if(campCadence<0)throw new IllegalArgumentException("camp cadence");
        this.buffer=Objects.requireNonNull(buffer);this.camps=Objects.requireNonNull(camps);this.campCadence=campCadence;
    }
    public static Tick campCutoff(Tick now,long cadence) {
        if(cadence<=0)return now;
        return new Tick(now.value()==0?0:(now.value()-1)/cadence*cadence);
    }
    public ObservationBatch seal(SnapshotBatch live,MemoryView before) {
        if(delivery!=null)return delivery;
        ordinary=buffer.seal(live,before.revisions());
        List<CampEpisodeObservation> observations=new ArrayList<>();
        for(var window:camps.sealed(campCutoff(live.capturedTick(),campCadence))) {
            AnchorRecord anchor=before.anchors().get(window.anchor());
            // Pending anchor observations cannot be promoted ahead of their durable creation.
            if(anchor==null)continue;
            if(!anchor.active())throw new IllegalStateException("drain camp observations before anchor deactivation");
            if(!anchor.regionIds().isEmpty()&&!anchor.regionIds().contains(window.primaryRegion()))throw new IllegalStateException("drain camp observations before topology change");
            observations.add(CampSampler.observation(window));
        }
        campIds=observations.stream().map(CampEpisodeObservation::windowId).toList();
        delivery=combine(ordinary,observations);return delivery;
    }
    private static ObservationBatch combine(ObservationBatch base,List<CampEpisodeObservation> camps) {
        List<ObservationId> ids=new ArrayList<>(base.inputIds());camps.forEach(c->ids.add(c.observationId()));ids.sort(ObservationId::compareTo);
        return new ObservationBatch(ids,base.observations(),base.beforeRevisions(),base.context(),camps,base.routeTransitions(),base.exposures());
    }
    public ObservationBatch rebaseAfterRejection(CommitAck ack,SnapshotBatch live,MemoryView before) {
        if(delivery==null)throw new IllegalStateException("no delivery");
        ordinary=buffer.rebaseAfterRejection(ack,live,before.revisions());
        delivery=combine(ordinary,delivery.campEpisodes());return delivery;
    }
    public void acknowledge(List<ObservationId> ids,Tick now) {
        if(delivery==null||ids.size()!=delivery.inputIds().size()||!new HashSet<>(ids).equals(new HashSet<>(delivery.inputIds())))throw new IllegalArgumentException("wrong combined acknowledgement");
        camps.acknowledge(campIds,now);buffer.acknowledge(ordinary.inputIds());
        delivery=null;ordinary=null;campIds=List.of();
    }
}
