package jsharp.lang;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method as a J# user-defined operator ({@code public static Money operator +(Money
 * a, Money b)} compiles to {@code plus(Money, Money)} with {@code @Operator("+")}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Operator {
  /** The operator symbol, e.g. {@code "+"} or {@code "<="}. */
  String value();
}
