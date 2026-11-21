package fr.proutlost.contract.director;

import java.util.Objects;
import static fr.proutlost.contract.director.Vocabulary.*;

/** Reviewed state registry entry. Ownership cannot be selected by incoming facts or event data. */
public record StateKeyDefinition(Id state_key, StateOwner owner, Id value_type, Revision value_version) {
    public StateKeyDefinition {
        Objects.requireNonNull(state_key); Objects.requireNonNull(owner);
        Objects.requireNonNull(value_type); Objects.requireNonNull(value_version);
    }
    public void validate(StateAddress address, StateValue value) {
        Checks.require(state_key.equals(address.state_key()) && owner == value.owner(), "State ownership mismatch");
        Checks.require(value_type.equals(value.value().type_id()) && value_version.equals(value.value().version()), "State codec mismatch");
    }
}
