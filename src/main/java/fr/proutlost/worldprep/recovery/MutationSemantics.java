package fr.proutlost.worldprep.recovery;

import java.util.Objects;

/** Shared fail-closed three-state decision for blocks and biomes. */
public final class MutationSemantics {
    public enum Decision { WRITE, NO_OP, CONFLICT }
    public static <T> Decision apply(T current,T before,T applied){return Objects.equals(current,before)?Decision.WRITE:Objects.equals(current,applied)?Decision.NO_OP:Decision.CONFLICT;}
    public static <T> Decision rollback(T current,T before,T applied){return Objects.equals(current,applied)?Decision.WRITE:Objects.equals(current,before)?Decision.NO_OP:Decision.CONFLICT;}
    private MutationSemantics(){}
}
