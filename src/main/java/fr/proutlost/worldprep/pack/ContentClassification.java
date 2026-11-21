package fr.proutlost.worldprep.pack;

/** Explicit production impact classification. Unknown installed content fails preflight. */
public enum ContentClassification {
    ENVIRONMENT_SEMANTIC, CONTENT_PROVIDER, RUNTIME_ONLY, PERFORMANCE_ONLY, OPTIONAL, UNCLASSIFIED;

    public boolean affectsEnvironment() {
        return this == ENVIRONMENT_SEMANTIC || this == CONTENT_PROVIDER;
    }
}
