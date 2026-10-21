package ann;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Every kind of element value, with and without defaults. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface Tag {
  String value();

  int priority() default 0;

  short small() default 1;

  Level level() default Level.LOW;

  Class<?> kind() default Object.class;

  String[] names() default {};

  enum Level {
    LOW,
    HIGH
  }
}
