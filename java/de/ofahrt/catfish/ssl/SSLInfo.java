package de.ofahrt.catfish.ssl;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLContext;

public record SSLInfo(SSLContext sslContext, X509Certificate certificate) {

  public SSLInfo {
    Objects.requireNonNull(sslContext, "sslContext");
    Objects.requireNonNull(certificate, "certificate");
  }

  /** Returns the CN from the certificate's subject, or null if absent. */
  public String certificateCommonName() {
    try {
      // X500Principal.getName() returns RFC 2253 format, which is the LDAP DN string format.
      // LdapName is the standard JDK class for parsing it.
      LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
      for (Rdn rdn : ldapName.getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return rdn.getValue().toString();
        }
      }
    } catch (InvalidNameException e) {
      // Malformed subject DN; treat as no CN.
    }
    return null;
  }

  /**
   * Returns true if this certificate covers the given hostname (RFC 2818 / RFC 6125 rules). Checks
   * DNS Subject Alternative Names first; falls back to CN only if no DNS SANs are present.
   */
  public boolean covers(String hostname) {
    if (hostname == null) {
      return false;
    }
    String lowerHostname = hostname.toLowerCase(Locale.ROOT);
    List<String> dnsSans = getDnsSans();
    if (!dnsSans.isEmpty()) {
      for (String san : dnsSans) {
        if (matchesHostname(san.toLowerCase(Locale.ROOT), lowerHostname)) {
          return true;
        }
      }
      return false;
    }
    // Fall back to CN (RFC 6125 §6.4.4 legacy behaviour)
    String cn = certificateCommonName();
    if (cn != null) {
      return matchesHostname(cn.toLowerCase(Locale.ROOT), lowerHostname);
    }
    return false;
  }

  private List<String> getDnsSans() {
    List<String> result = new ArrayList<>();
    try {
      Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
      if (sans == null) {
        return result;
      }
      for (List<?> san : sans) {
        // type 2 = dNSName
        if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
          Object value = san.get(1);
          if (value instanceof String) {
            result.add((String) value);
          }
        }
      }
    } catch (CertificateParsingException e) {
      // ignore; treat as no SANs
    }
    return result;
  }

  private static boolean matchesHostname(String pattern, String hostname) {
    if (pattern.startsWith("*.")) {
      String suffix = pattern.substring(1); // ".example.com"
      if (hostname.endsWith(suffix)) {
        String left = hostname.substring(0, hostname.length() - suffix.length());
        // Wildcard covers exactly one label (no dots in the matched part)
        return !left.isEmpty() && left.indexOf('.') < 0;
      }
      return false;
    }
    return pattern.equals(hostname);
  }
}
