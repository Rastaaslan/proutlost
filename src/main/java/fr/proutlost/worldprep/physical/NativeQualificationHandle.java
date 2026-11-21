package fr.proutlost.worldprep.physical;

import java.io.IOException;

/** Explicit protocol-specific native qualification lifecycle. Never auto-started. */
interface NativeQualificationHandle extends AutoCloseable {
    void tick();
    void pause();
    void resume();
    String status();
    @Override void close() throws IOException;
}
