package de.ofahrt.catfish.utils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Parses and formats HTTP date values as defined by RFC 7231 §7.1.1.1.
 *
 * <p>All three historical date formats are accepted on input; output always uses the RFC 7231
 * preferred format: {@code "Sun, 06 Nov 1994 08:49:37 GMT"}.
 *
 * <p>All methods are thread-safe.
 */
public final class HttpDate {

  // RFC 1123 / RFC 822: "Sun, 06 Nov 1994 08:49:37 GMT"
  // z parses the timezone from the input (always present); withZone(UTC) is a harmless fallback.
  // Also accepts the "GMT+00:00" variant encountered in the wild.
  private static final DateTimeFormatter FORMAT_RFC1123 =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
          .withZone(ZoneOffset.UTC);

  // RFC 850 (obsoleted by RFC 1036): "Sunday, 06-Nov-94 08:49:37 GMT"
  // appendValueReduced(YEAR, 2, 2, 1970): 2-digit years relative to 1970 — 94→1994, 00→2000.
  // withZone(UTC) is a harmless fallback (zone always present in input).
  private static final DateTimeFormatter FORMAT_RFC850 =
      new DateTimeFormatterBuilder()
          .appendPattern("EEEE, dd-MMM-")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
          .appendPattern(" HH:mm:ss z")
          .toFormatter(Locale.US)
          .withZone(ZoneOffset.UTC);

  // ANSI C asctime(): "Sun Nov  6 08:49:37 1994"
  // Optional extra space handles space-padded single-digit days ("  6" vs " 6").
  // No timezone in input — withZone(UTC) is essential for Instant.from() to succeed.
  private static final DateTimeFormatter FORMAT_ASCTIME =
      new DateTimeFormatterBuilder()
          .appendPattern("EEE MMM ")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
          .appendPattern(" HH:mm:ss yyyy")
          .toFormatter(Locale.US)
          .withZone(ZoneOffset.UTC);

  // RFC 7231 §7.1.1.1 preferred output: "Sun, 06 Nov 1994 08:49:37 GMT"
  // 'GMT' is a quoted literal — using z would emit "GMT+00:00" or "UTC" depending on the JVM.
  // withZone(UTC) decomposes the Instant into UTC calendar fields before formatting.
  private static final DateTimeFormatter FORMAT_OUTPUT =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
          .withZone(ZoneOffset.UTC);

  // Tried in order: RFC1123 is most common; RFC850 and asctime are legacy formats.
  private static final DateTimeFormatter[] PARSE_FORMATTERS = {
    FORMAT_RFC1123, FORMAT_RFC850, FORMAT_ASCTIME
  };

  /**
   * Formats an instant as an HTTP date string.
   *
   * <p>Output format: {@code "Sun, 06 Nov 1994 08:49:37 GMT"} (RFC 7231 §7.1.1.1).
   *
   * @param date the instant to format
   * @return the formatted date string
   */
  public static String formatDate(Instant date) {
    return FORMAT_OUTPUT.format(date);
  }

  /**
   * Parses an HTTP date string and returns the corresponding instant.
   *
   * <p>Accepts all three formats defined by RFC 7231 §7.1.1.1:
   *
   * <ul>
   *   <li>RFC 1123: {@code "Sun, 06 Nov 1994 08:49:37 GMT"}
   *   <li>RFC 850: {@code "Sunday, 06-Nov-94 08:49:37 GMT"}
   *   <li>asctime: {@code "Sun Nov 6 08:49:37 1994"}
   * </ul>
   *
   * @param date the date string to parse
   * @return the parsed instant, or {@code null} if parsing fails
   */
  public static Instant parseDate(String date) {
    for (DateTimeFormatter fmt : PARSE_FORMATTERS) {
      try {
        return Instant.from(fmt.parse(date));
      } catch (DateTimeParseException ignored) {
      }
    }
    return null;
  }

  private HttpDate() {
    // Not instantiable.
  }
}
