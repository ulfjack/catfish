package de.ofahrt.catfish.verify;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lean modules whose definitions the Verify.* / @Returns spec strings in this class refer to. The
 * generator emits {@code import <name>} and {@code open <name>} for each, so the spec's module
 * dependencies live in the Java source rather than the BUILD file. Each name must be both a Lean
 * module (file) and the namespace it declares.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ImportLeanPackage {
  String[] value();
}
