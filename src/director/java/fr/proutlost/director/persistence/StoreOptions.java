package fr.proutlost.director.persistence;

import fr.proutlost.director.api.model.*;

/** Technical capacities injected from the central profile; no gameplay approval is implied. */
public record StoreOptions(int frameBytes, int transactionBytes, long segmentBytes, long queueBytes,
                           int queueTasks, long minimumFreeBytes, int consumerBatchLimit,
                           int writeBatchBytes, int checkpointGenerations) {
    public StoreOptions {
        if (frameBytes < WalFrame.OVERHEAD || transactionBytes < 128
                || segmentBytes < transactionBytes || queueBytes < transactionBytes || queueTasks < 1
                || minimumFreeBytes < 0 || consumerBatchLimit < 1 || consumerBatchLimit > 1024
                || writeBatchBytes < 1 || writeBatchBytes > queueBytes || checkpointGenerations < 2)
            throw new IllegalArgumentException("store capacity limits");
    }
    public StoreOptions(int frameBytes,int transactionBytes,long segmentBytes,long queueBytes,
                        int queueTasks,long minimumFreeBytes,int consumerBatchLimit) {
        this(frameBytes,transactionBytes,segmentBytes,queueBytes,queueTasks,minimumFreeBytes,
            consumerBatchLimit,transactionBytes,3);
    }
    /** Configuration remains an API DTO. Frozen config implementation classes are not imported. */
    public static StoreOptions fromConfig(ConfigView config,int queueTasks,int consumerBatchLimit) {
        int frame=Math.toIntExact(value(config,"io.frame_max_bytes"));
        return new StoreOptions(frame,frame,value(config,"io.segment_bytes"),value(config,"io.queue_bytes"),
            queueTasks,value(config,"io.disk_low_free_bytes"),consumerBatchLimit,
            Math.toIntExact(value(config,"io.batch_bytes")),Math.toIntExact(value(config,"io.checkpoint_generations")));
    }
    private static long value(ConfigView config,String key) {
        var value=config.values().get(key);
        if(value==null)throw new IllegalArgumentException("missing numeric capacity: "+key);
        return value.value().longValueExact();
    }
}
