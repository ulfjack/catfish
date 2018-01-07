package de.ofahrt.catfish.utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpFieldName {

  public static final String ACCEPT              = "Accept";
  public static final String ACCEPT_CHARSET      = "Accept-Charset";
  public static final String ACCEPT_ENCODING     = "Accept-Encoding";
  public static final String ACCEPT_LANGUAGE     = "Accept-Language";
  public static final String ACCEPT_RANGES       = "Accept-Ranges";
  public static final String AGE                 = "Age";
  public static final String ALLOW               = "Allow";
  public static final String AUTHORIZATION       = "Authorization";
  public static final String CACHE_CONTROL       = "Cache-Control";
  public static final String CONNECTION          = "Connection";
  public static final String CONTENT_ENCODING    = "Content-Encoding";
  public static final String CONTENT_LANGUAGE    = "Content-Language";
  public static final String CONTENT_LENGTH      = "Content-Length";
  public static final String CONTENT_LOCATION    = "Content-Location";
  public static final String CONTENT_MD5         = "Content-MD5";
  public static final String CONTENT_RANGE       = "Content-Range";
  public static final String CONTENT_TYPE        = "Content-Type";
  public static final String COOKIE              = "Cookie";
  public static final String DATE                = "Date";
  public static final String ETAG                = "ETag";
  public static final String EXPECT              = "Expect";
  public static final String EXPIRES             = "Expires";
  public static final String FROM                = "From";
  public static final String HOST                = "Host";
  public static final String IF_MATCH            = "If-Match";
  public static final String IF_MODIFIED_SINCE   = "If-Modified-Since";
  public static final String IF_NONE_MATCH       = "If-None-Match";
  public static final String IF_RANGE            = "If-Range";
  public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
  public static final String LAST_MODIFIED       = "Last-Modified";
  public static final String LOCATION            = "Location";
  public static final String MAX_FORWARDS        = "Max-Forwards";
  public static final String PRAGMA              = "Pragma";
  public static final String PROXY_AUTHENTICATE  = "Proxy-Authenticate";
  public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
  public static final String RANGE               = "Range";
  public static final String REFERER             = "Referer";
  public static final String RETRY_AFTER         = "Retry-After";
  public static final String SERVER              = "Server";
  public static final String SET_COOKIE          = "Set-Cookie";
  public static final String TE                  = "TE";
  public static final String TRAILER             = "Trailer";
  public static final String TRANSFER_ENCODING   = "Transfer-Encoding";
  public static final String UPGRADE             = "Upgrade";
  public static final String USER_AGENT          = "User-Agent";
  public static final String VARY                = "Vary";
  public static final String VIA                 = "Via";
  public static final String WARNING             = "Warning";
  public static final String WWW_AUTHENTICATE    = "WWW-Authenticate";

  private static Map<String,String> CANONICALIZATION_MAP = getCanonicalizationMap();

  private static Map<String,String> getCanonicalizationMap() {
  	Map<String,String> result = new HashMap<>();
  	for (Field field : HttpFieldName.class.getFields()) {
  		if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()) &&
  				Modifier.isFinal(field.getModifiers()) && String.class.equals(field.getType())) {
  			try {
  				String value = (String) field.get(null);
  				result.put(value.toLowerCase(Locale.US), value);
  				result.put(value, value);
  			} catch (IllegalAccessException e) {
  			  // should never happen
  				throw new RuntimeException(e);
  			}
  		}
  	}
  	return result;
  }

  /**
   * Returns a canonical representation of the given HTTP header field name.
   * If known, it returns a representation with a well-known capitalization.
   * Otherwise, it returns an all-lowercase representation.
   */
  public static String canonicalize(String name) {
  	String result = CANONICALIZATION_MAP.get(name);
  	if (result != null) return result;
  	name = name.toLowerCase(Locale.US);
  	result = CANONICALIZATION_MAP.get(name);
  	return result != null ? result : name;
  }

  /**
   * Returns whether the given two HTTP header field names are equal.
   */
  public static boolean areEqual(String expected, String actual) {
  	return of(expected).matches(actual);
  }

	public static interface Matcher {
		/**
		 * Returns whether the value matches the field name stored in this matcher.
		 */
		boolean matches(String value);
	}

  /**
   * Returns a matcher for the given http field name.
   */
  public static Matcher of(String name) {
  	final String canonicalName = canonicalize(name);
  	return new Matcher()
  		{
  			@Override
  			public boolean matches(String value)
  			{ return canonicalName.equals(canonicalize(value)); }
  		};
  }

  private HttpFieldName() {
    // Not instantiable.
  }
}
