package fr.proutlost.director.api.model;
import java.util.*;
/** A bounded native metadata plan page. A staged page is never a visible head. */
public record MetadataGenerationPage(Fingerprint generation,ResourceId sourceNamespace,
        Revision exportRevision,Fingerprint exportDigest,int pageIndex,int pageCount,
        List<AggregateState> states,Map<AggregateKey,Revision> beforeRevisions) {
    public MetadataGenerationPage {
        Objects.requireNonNull(generation);Objects.requireNonNull(sourceNamespace);
        Objects.requireNonNull(exportRevision);Objects.requireNonNull(exportDigest);
        states=List.copyOf(states);beforeRevisions=Map.copyOf(beforeRevisions);
        Checks.require(pageCount>0&&pageCount<=1024&&pageIndex>=0&&pageIndex<pageCount,"metadata page index/count");
        Checks.require(states.size()<=96,"metadata page entry bound");
        Set<AggregateKey> keys=new HashSet<>();
        for(var state:states){
            Checks.require(state instanceof RegionAggregate||state instanceof RegionMemoryAggregate
                ||state instanceof LocationAggregate||state instanceof AnchorAggregate,"metadata state kind");
            Checks.require(keys.add(state.key()),"metadata duplicate page key");
            var before=beforeRevisions.get(state.key());
            Checks.require(before!=null&&state.header().revision().equals(before.plus(1)),"metadata planned revision");
        }
        Checks.require(beforeRevisions.size()<=96&&beforeRevisions.keySet().containsAll(keys),"metadata page exact CAS guards");
    }
}
