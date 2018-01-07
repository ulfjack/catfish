package de.ofahrt.catfish;

import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;

import de.ofahrt.catfish.utils.HttpFieldName;

final class HttpFieldHelper {

  private static final HashSet<String> MULTIPLE_OCCURANCE_BLACKLIST = new HashSet<String>(Arrays.asList(
      HttpFieldName.HOST
  ));

  // RFC 3986: Uniform Resource Identifier (URI): Generic Syntax
  private static final String HEXDIG = "[0-9A-F]";

  // unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
  private static final String UNRESERVED = "[0-9a-zA-Z._~-]";
  // sub-delims = "!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="
  private static final String SUBDELIMS = "[!$&'()*+,;=]";
  // pct-encoded = "%" HEXDIG HEXDIG
  private static final String PCT_ENCODED = "(?:%" + HEXDIG + HEXDIG + ")";
  private static final String REG_NAME = "(?:(?:" + UNRESERVED + "|" + SUBDELIMS + "|" + PCT_ENCODED + ")*)";

  // dec-octet     = DIGIT                 ; 0-9
  //               / %x31-39 DIGIT         ; 10-99
  //               / "1" 2DIGIT            ; 100-199
  //               / "2" %x30-34 DIGIT     ; 200-249
  //               / "25" %x30-35          ; 250-255
  private static final String DEC_OCTET =
      "(?:[0-9]|(?:[1-9][0-9])|(?:1[0-9][0-9])|(?:2[0-4][0-9])|(?:25[0-5]))";
  // IPv4address = dec-octet "." dec-octet "." dec-octet "." dec-octet
  private static final String IPV4 = DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET;

  // host = IP-literal / IPv4address / reg-name
  private static final String HOST = "(?:" + REG_NAME + "|" + IPV4 + ")";

  // RFC 2616: Hypertext Transfer Protocol -- HTTP/1.1
  // Host = "Host" ":" host [ ":" port ]
  private static final Pattern HOST_PORT_PATTERN = Pattern.compile(
      "(" + HOST + ")(:\\d*)?");

  /**
   * Implements a syntax check for hostport according to RFC 3986. Does not support IPv6 addresses
   * or future IP literals.
   */
  public static boolean validHostPort(String text) {
    return HOST_PORT_PATTERN.matcher(text).matches();
  }

  public static boolean mayOccurMultipleTimes(String fieldName) {
    if (MULTIPLE_OCCURANCE_BLACKLIST.contains(fieldName)) {
      return false;
    }
    return true;
  }
}
