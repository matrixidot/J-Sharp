package org.jetbrains.annotations;

import java.lang.annotation.*;

/** Stand-in for the JetBrains annotation of the same name. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface Nullable {}
