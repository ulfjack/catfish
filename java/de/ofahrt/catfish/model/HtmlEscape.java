package de.ofahrt.catfish.model;

/** HTML escaping utilities. */
public final class HtmlEscape {

  /**
   * Escapes a string for use as HTML element text content. Escapes {@code &}, {@code <}, {@code >}.
   * Not safe for HTML attributes — use {@link #attribute(String)} instead.
   */
  public static String text(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * Escapes a string for use inside a double-quoted HTML attribute. Escapes {@code &}, {@code <},
   * {@code >}, and {@code "}.
   */
  public static String attribute(String value) {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private HtmlEscape() {}
}
