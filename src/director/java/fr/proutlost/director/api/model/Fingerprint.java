package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public record Fingerprint(String value) { public Fingerprint { require(value != null && value.matches("[a-f0-9]{64}"), "SHA256"); } }
