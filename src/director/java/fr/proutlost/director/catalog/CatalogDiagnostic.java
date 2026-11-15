package fr.proutlost.director.catalog;

import fr.proutlost.director.api.error.ReasonCode;

/** A rejected document never changes the installed generation. Pointer uses RFC 6901. */
public record CatalogDiagnostic(String file, String pointer, ReasonCode code, String detail) {}
