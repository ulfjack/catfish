package de.ofahrt.catfish.verify;

/**
 * Cut-point markers. Each call names a proof obligation site; the string is a Lean {@code Prop}
 * spliced into the generated obligations, elaborated in a scope where the method's locals are bound
 * to their source-level names.
 *
 * <p>The bodies are empty: C2 inlines and removes them once the method is hot. They must NOT be
 * guarded by a compile-time constant, or javac elides the call and the cut point disappears from
 * the class file.
 */
public final class Verify {
  private Verify() {}

  public static void requires(String spec) {}

  public static void invariant(String spec) {}

  public static void ensure(String spec) {}

  public static void assume(String spec) {} // grep for these: each is a hole
}
