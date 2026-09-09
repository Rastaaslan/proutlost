package fr.proutlost.worldprep.pipeline;
/** Scheduling knobs are deliberately excluded from semantic fingerprints. */
public record ExecutionConfig(int cellsPerTick,int batchSize,int workerCount){public ExecutionConfig{if(cellsPerTick<1||batchSize<1||workerCount<1)throw new IllegalArgumentException();}}
