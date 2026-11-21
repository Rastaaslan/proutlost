package fr.proutlost.director.catalog;

import fr.proutlost.contract.director.*;
import fr.proutlost.worldprep.director.CatalogImportProbe;

/** Development qualification only; never in the WorldPrep production artifact. */
public final class QualificationImportProbe implements CatalogImportProbe {
    @Override public CatalogSnapshot importAndReadAdmitted(String json,Id world,Id build) {
        var consumer=new ReadyCatalog(world,build);
        consumer.importJson(json);
        if(!consumer.admitting())throw new IllegalStateException("Actual D2 consumer refused admission");
        return consumer.snapshot();
    }
}
