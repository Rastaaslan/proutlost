package fr.proutlost.p008qa.cohort;

/** Mirrors the existing native health reader: Post may precede vanilla's write
 * for the current tick. Only currentUnsigned-1 is final. Never use current slot,
 * wall interval, TPS or a Pre/Post callback window as that duration.
 */
public final class PreviousCompletedTickReader {
    private PreviousCompletedTickReader(){}
    public static CompletedTickCapture.Offer observe(int currentTick,long[] ring,long wallNs,int players,CompletedTickCapture capture){
        long current=Integer.toUnsignedLong(currentTick);
        if(current<=1){capture.startupSkipped();return CompletedTickCapture.Offer.STARTUP_SKIPPED;}
        if(ring==null||ring.length==0||ring.length>4096){capture.sourceFailure("VANILLA_COMPLETED_TICK_RING_UNAVAILABLE");return CompletedTickCapture.Offer.INVALID;}
        long completed=current-1;
        return capture.offer(completed,wallNs,ring[(int)(completed%ring.length)],players);
    }
}
