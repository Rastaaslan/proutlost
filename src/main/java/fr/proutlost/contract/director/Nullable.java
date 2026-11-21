package fr.proutlost.contract.director;

import java.lang.annotation.*;

/** A present wire field may contain null; omission is still rejected. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.FIELD})
public @interface Nullable {}
