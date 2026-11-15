package fr.proutlost.worldprep.physical;

import java.io.IOException;

/** Reject DEV-only v4 input before any candidate resource, engine or world mutation. */
public final class QualificationRuntimeGate {
    public static void requireAllowed(String directorReceipt, boolean production) throws IOException {
        if(directorReceipt==null||directorReceipt.isBlank())
            throw new IOException("Explicit physical Director importer/receipt required");
        if(production && "DEV_SERVICE_PROBE".equals(directorReceipt))
            throw new IOException("DEV_SERVICE_PROBE refused at production start BEFORE physical work");
    }
    private QualificationRuntimeGate(){}
}
