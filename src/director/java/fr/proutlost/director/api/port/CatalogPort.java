package fr.proutlost.director.api.port;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.permission.CommitPermit;
import java.util.concurrent.CompletionStage;
/** G0 contract. Completion callbacks deliver data only; world access requires the server actor. */
public interface CatalogPort { CatalogSnapshot current(); }
