package fr.proutlost.director.api.model;

import java.util.*;

/** A bounded byte fragment, never an independently applicable LocationImport revision. */
public record LocationTransferPage(Fingerprint manifestSha256, int pageIndex, String bytesBase64) {
    public LocationTransferPage {
        Objects.requireNonNull(manifestSha256); Objects.requireNonNull(bytesBase64);
        Checks.require(pageIndex >= 0 && pageIndex < 53 && bytesBase64.length() <= 26_668,
                "location transfer page bound");
    }
}
