package jsharp.lang;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a parameter's compile-time default value for J# callers. The compiler writes the value
 * with the parameter's own constant type (the declared {@code String} element type is nominal); a
 * missing value means {@code null}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface DefaultValue {
  String value() default "";
}
