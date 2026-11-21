package fr.proutlost.director.api.model;
import java.util.Objects;
/** Read-only selector, never an execution permission. */
public record MutationInspectionPayload(MutationId mutationId) implements AdminPayload {
    public MutationInspectionPayload {Objects.requireNonNull(mutationId);}
}
