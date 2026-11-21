package fr.proutlost.director.api.model;
import java.util.Objects;
/** Explicit mutation lookup; its UUID is never interpreted as an EventInstanceId. */
public record MutationFilter(MutationId mutationId) implements AdminFilter {
    public MutationFilter {Objects.requireNonNull(mutationId);}
}
