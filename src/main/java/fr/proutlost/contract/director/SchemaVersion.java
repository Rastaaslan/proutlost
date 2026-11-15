package fr.proutlost.contract.director;

public record SchemaVersion(String value) {
    public static final SchemaVersion BASE = new SchemaVersion("1.0.0");
    public static final SchemaVersion EXECUTION = new SchemaVersion("1.1.0");
    public SchemaVersion { Checks.require("1.0.0".equals(value) || "1.1.0".equals(value), "Unsupported required schema"); }
}
