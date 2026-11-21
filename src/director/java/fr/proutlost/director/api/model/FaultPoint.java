package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public enum FaultPoint { BLOB_WRITTEN, BLOB_FORCED, TX_BEGIN, PREPARE_FORCED, ACK_CONSUMED, FIRST_SLICE, LOCAL_MARKER, CHUNK_SAVED, ENTITY_SAVED, VERIFIED, RESULT_WAL, OUTBOX_SENT, CONSUMER_ACK, CHECKPOINT_FORCED, MANIFEST_RENAMED, SEGMENT_REMOVED }
