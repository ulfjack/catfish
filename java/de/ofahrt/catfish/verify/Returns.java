package de.ofahrt.catfish.verify;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method postcondition: a Lean expression, over the method's parameters, that every returned value
 * satisfies. The generator uses it two ways: as the obligation {@code ret = <value>} synthesised at
 * each {@code return}, and as the callee's contract substituted at call sites (replacing
 * calls.map). CLASS retention keeps it in the class file without needing it at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Returns {
  String value();
}
