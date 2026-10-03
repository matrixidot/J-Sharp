package shop;

import java.lang.annotation.*;

/** A nullness annotation declared in the module itself; J# recognizes it by name. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
public @interface Nullable {}
