package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public final class AiTransportResponse {
 private final byte[] body; private final long receivedMonotonicNanos;
 public AiTransportResponse(byte[] body,long receivedMonotonicNanos){require(body.length<=1048576,"AI response bound");this.body=body.clone();this.receivedMonotonicNanos=receivedMonotonicNanos;}
 public byte[] body(){return body.clone();} public long receivedMonotonicNanos(){return receivedMonotonicNanos;}
}
