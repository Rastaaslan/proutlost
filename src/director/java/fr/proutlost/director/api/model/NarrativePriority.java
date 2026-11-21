package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Internal request priority, independent of content conflicts.priority. Larger ranks precede smaller. */
public record NarrativePriority(int rank) {public static final NarrativePriority NORMAL=new NarrativePriority(0);public NarrativePriority {require(rank>=-1024&&rank<=1024,"narrative priority bound");}}
