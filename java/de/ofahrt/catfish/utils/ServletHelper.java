package de.ofahrt.catfish.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ServletHelper {

private static final String DEFAULT_CHARSET = "UTF-8";

private static final String queryPartStructure = "([^&=]*)=([^&]*)";
private static final Pattern queryPartPattern = Pattern.compile(queryPartStructure);

private static final Pattern nameExtractorPattern = Pattern.compile(".* name=\"(.*)\".*");
private static final Pattern filenameExtractorPattern = Pattern.compile(".* name=\"(.*)\".* filename=\"(.*)\".*");

public static final String formatText(String what, boolean fixed) {
  StringBuffer result = new StringBuffer();
  if (fixed) result.append("<pre>");
  for (int i = 0; i < what.length(); i++) {
    char c = what.charAt(i);
    switch (c) {
      case 13 :
        break;
      case 10 :
        result.append("<br/>");
        break;
      case '<' :
        result.append("&lt;");
        break;
      case '>' :
        result.append("&gt;");
        break;
      case '&' :
        result.append("&amp;");
        break;
      case '"' :
        result.append("&quot;");
        break;
      default :
        result.append(c);
        break;
    }
  }
  if (fixed) result.append("</pre>");
  return result.toString();
}

//public static void generate(ResponseCode code, MimeType mimeType, HttpServletResponse response, Generator generator) throws IOException
//{
//	StringWriter temp = new StringWriter(5000);
//	generator.generate(temp);
//	response.setHeader(HeaderKey.CACHE_CONTROL.toString(), "no-cache");
//	response.setHeader(HeaderKey.PRAGMA.toString(), "no-cache");
//	response.setStatus(code.getCode());
//	response.setContentType(mimeType.toString());
//	response.setCharacterEncoding("UTF-8");
//	Writer sout = response.getWriter();
//	sout.append(temp.getBuffer());
//}
//
//public static void generate(MimeType mimeType, HttpServletResponse response, Generator generator) throws IOException
//{ generate(ResponseCode.OK, mimeType, response, generator); }

public static void setBodyString(HttpServletResponse response, MimeType mimeType, String s) throws IOException
{
	response.setContentType(mimeType.toString());
	Writer out = response.getWriter();
	out.write(s);
	out.close();
}

public static void setBodyInputStream(HttpServletResponse response, MimeType type, InputStream in) throws IOException
{
	response.setContentType(type.toString());
  OutputStream out = response.getOutputStream();
  int i = 0;
  byte[] buffer = new byte[1024];
  while ((i = in.read(buffer)) != -1)
    out.write(buffer, 0, i);
  out.close();
}

public static void setBodyReader(HttpServletResponse response, MimeType type, Reader in) throws IOException
{
	response.setContentType(type.toString());
  Writer out = response.getWriter();
  int i = 0;
  char[] buffer = new char[1024];
  while ((i = in.read(buffer)) != -1)
    out.write(buffer, 0, i);
  out.close();
}

public static void setBodyFile(HttpServletResponse response, MimeType type, String name) throws IOException
{
  File f = new File(name);
  if (!f.canRead()) throw new IOException("Cannot read file \""+name+"\"!");
  
  FileInputStream fis = null;
  try
  {
    fis = new FileInputStream(name);
    setBodyInputStream(response, type, fis);
  }
  finally
  { if (fis != null) fis.close(); }
}

public static String getFilename(HttpServletRequest request) {
	try {
		String filename = new URI(request.getRequestURI()).getPath();
		int j = filename.lastIndexOf('/');
		if (j != -1) {
			filename = filename.substring(j + 1);
		}
		if ("".equals(filename)) {
		  filename = "index";
		}
		return filename;
	} catch (URISyntaxException e) {
	  throw new RuntimeException(e);
	}
}

public static boolean supportCompression(HttpServletRequest request) {
  String temp = request.getHeader(HttpFieldName.ACCEPT_ENCODING);
  if (temp != null) {
    if (temp.toLowerCase(Locale.ENGLISH).indexOf("gzip") >= 0) {
      return true;
    }
  } else {
    temp = request.getHeader("~~~~~~~~~~~~~~~");
    if ("~~~~~ ~~~~~~~".equals(temp)) {
      return true;
    }
    temp = request.getHeader("---------------");
    if ("----- -------".equals(temp)) {
      return true;
    }
  }
  return false;
}

public static Map<String, String> parseQuery(HttpServletRequest request) {
	Map<String, String> result = new TreeMap<>();
	String queryData = request.getQueryString();
	if (queryData != null) {
		Matcher mq = queryPartPattern.matcher(queryData);
		while (mq.find()) {
			try {
				String key = URLDecoder.decode(mq.group(1), DEFAULT_CHARSET);
				String value = URLDecoder.decode(mq.group(2), DEFAULT_CHARSET);
				result.put(key, value);
			} catch (UnsupportedEncodingException e) {
			  throw new RuntimeException("\"" + queryData + "\"", e);
			}
		}
	}
	return result;
}

private static byte[] readByteArray(InputStream in, int length) throws IOException {
	int bodyReceived = 0;
	byte[] ba = new byte[length];
	while (bodyReceived < ba.length) {
		int i = in.read(ba, bodyReceived, ba.length-bodyReceived);
		if (i == -1) {
			throw new IOException("Unexpected end of stream!");
		}
		bodyReceived += i;
	}
	return ba;
}

private static void parseFormData(FormData result, InputStream in, int expected) throws IOException {
	boolean requestFinished = false;
	int b = 0;
	String line = "";
	Map<String, String> tempInfo = new TreeMap<>();
	while (!requestFinished) {
		b = in.read();
		if (b < 0) {
			b = -1;
		} else {
			expected--;
			if ((b < 9) || (b > 255)) {
			  b = 0;
			}
			if ((b > 10) && (b < 32)) {
			  b = 0;
			}
		}
		
		switch (b) {
			case -1 : requestFinished = true; break;
			case 0 : break;
			case 10 :
				if (line.equals("")) {
					requestFinished = true;
				} else {
					int i = line.indexOf(':');
					// FIXME: Use canonicalizer!
					String s1 = line.substring(0, i).trim().toLowerCase(Locale.ENGLISH);
					String s2 = line.substring(i+1).trim();
					tempInfo.put(s1, s2);
				}
				line = "";
				break;
			default :
				line += (char) b;
				break;
		}
	}
	
	byte[] ba = readByteArray(in, expected-2);
	String contentType = tempInfo.get("content-type");
	String contentDisposition = tempInfo.get("content-disposition");
	if (contentType == null) {
		Matcher m = nameExtractorPattern.matcher(contentDisposition);
		if (m.matches()) {
			String name = m.group(1);
			result.data.put(name, new String(ba));
		} else {
			throw new IllegalArgumentException("Unrecognized form data!");
		}
	} else {
		Matcher m = filenameExtractorPattern.matcher(contentDisposition);
		if (m.matches()) {
			String name = m.group(1);
			String tempname = m.group(2);
			result.files.put(name, new FileData(name, tempname, ba));
		} else {
			throw new IllegalArgumentException("Unrecognized form data!");
		}
	}
}

static FormData parseFormData(int clen, InputStream in, String ctHeader) throws IOException {
	FormData result = new FormData();
	byte[] ba = readByteArray(in, clen);
	String contentType = ctHeader;
	{
		int i = ctHeader.indexOf(';');
		if (i >= 0) {
			contentType = ctHeader.substring(0, i).trim();
			ctHeader = ctHeader.substring(i+1).trim();
		}
	}
	
	if (contentType.equalsIgnoreCase("multipart/form-data")) {
		int k = ctHeader.indexOf("=");
		String boundary = "--"+ctHeader.substring(k+1).trim();
		byte[] bounds = boundary.getBytes();
		int numIndex = 0;
		int[] indexes = new int[30];
		
		for (int i = 0; i < ba.length-bounds.length; i++) {
			boolean found = true;
			for (int j = 0; j < bounds.length; j++) {
				if (ba[i+j] != bounds[j]) {
					found = false;
					break;
				}
			}
			
			if (found) {
				indexes[numIndex++] = i;
			}
		}
		
		for (int i = 0; i < numIndex-1; i++) {
			int start = indexes[i]+bounds.length+2;
			int length = indexes[i+1]-indexes[i]-bounds.length-2;
			parseFormData(result, new ByteArrayInputStream(ba, start, length), length);
		}
	}
	
	if (contentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
//				String ce = request.getHeader(HeaderKey.CONTENT_ENCODING.toString());
		String footerData = new String(ba);
		
		Matcher mq = queryPartPattern.matcher(footerData);
		while (mq.find()) {
			try {
				String first = URLDecoder.decode(mq.group(1), DEFAULT_CHARSET);
				String last = URLDecoder.decode(mq.group(2), DEFAULT_CHARSET);
				result.data.put(first, last);
			} catch (UnsupportedEncodingException e) {
				IOException e2 = new IOException("Internal Error!");
				e2.initCause(e);
				throw e2;
			}
		}
	}
	return result;
}

public static FormData parseFormData(HttpServletRequest request) throws IOException {
	String clen = request.getHeader(HttpFieldName.CONTENT_LENGTH);
	String ctHeader = request.getHeader(HttpFieldName.CONTENT_TYPE);
	if ((clen != null) && (ctHeader != null)) {
		return parseFormData(Integer.parseInt(clen), request.getInputStream(), ctHeader);
	}
	return new FormData();
}

public static String getCompleteUrl(HttpServletRequest request) {
	StringBuffer result = request.getRequestURL();
	String query = request.getQueryString();
	if (query != null) {
		result.append("?").append(query);
	}
	return result.toString();
}

public static final void printRequest(PrintStream out, HttpServletRequest request) {
  out.println(request.getMethod() + " " + getCompleteUrl(request) + " " + request.getProtocol());
  Enumeration<?> it = request.getHeaderNames();
  while (it.hasMoreElements()) {
  	String key = (String) it.nextElement();
  	out.println(key+": "+request.getHeader(key));
  }
  out.println("Query Parameters:");
  Map<String,String> queries = parseQuery(request);
  for (Map.Entry<String,String> e : queries.entrySet()) {
  	out.println("  "+e.getKey()+": "+e.getValue());
  }
  try {
  	FormData formData = parseFormData(request);
	  out.println("Post Parameters:");
	  for (Map.Entry<String,String> e : formData.data.entrySet()) {
	  	out.println("  "+e.getKey()+": "+e.getValue());
	  }
  } catch (IllegalArgumentException e) {
  	out.println("Exception trying to parse post parameters:");
  	e.printStackTrace(out);
  } catch (IOException e) {
  	out.println("Exception trying to parse post parameters:");
  	e.printStackTrace(out);
  }
  out.flush();
}

public static final String getRequestText(HttpServletRequest req) {
  ByteArrayOutputStream ba = new ByteArrayOutputStream();
  PrintStream out = new PrintStream(ba);
  printRequest(out, req);
  return formatText(ba.toString(), true);
}

//       token          = 1*<any CHAR except CTLs or separators>
//       CTL            = <any US-ASCII control character
//                        (octets 0 - 31) and DEL (127)>
//       separators     = "(" | ")" | "<" | ">" | "@"
//                      | "," | ";" | ":" | "\" | <">
//                      | "/" | "[" | "]" | "?" | "="
//                      | "{" | "}" | SP | HT
private static final String TOKEN_CHARS = "[^\\p{Cntrl}()<>@,;:\\\\\"/?={} \t\\[\\]]";
private static final String TOKEN = TOKEN_CHARS+"+";

private static final Pattern CONTENT_TYPE_PATTERN_EXPLICIT_MIME_TYPE = Pattern.compile(
		"("+TOKEN+"/"+TOKEN+")"+"((?:\\s*;\\s*"+TOKEN+"=(?:"+TOKEN+"|\"(?:\\\\[\\p{ASCII}]|[^\"\\\\])*\"))*)");

private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile(
		"("+TOKEN+")/("+TOKEN+")"+"((?:\\s*;\\s*"+TOKEN+"=(?:"+TOKEN+"|\"(?:\\\\[\\p{ASCII}]|[^\"\\\\])*\"))*)");

private static final Pattern PARAMETER_PATTERN = Pattern.compile(
		"(?:\\s*;\\s*("+TOKEN+")=(?:("+TOKEN+")|\"((?:\\\\[\\p{ASCII}]|[^\"\\\\])*)\"))");

private static final Set<String> COMMON_CONTENT_TYPES = new HashSet<>(Arrays.asList("text/html"));

static boolean isTokenCharacter(char c) {
  return Pattern.compile(TOKEN_CHARS).matcher("" + c).matches();
}

//       media-type     = type "/" subtype *( ";" parameter )
//       type           = token
//       subtype        = token
//       parameter               = attribute "=" value
//       attribute               = token
//       value                   = token | quoted-string
public static boolean isValidContentType(String name) {
	if (name == null) {
	  throw new NullPointerException();
	}
	Matcher m = CONTENT_TYPE_PATTERN.matcher(name);
	return m.matches();
}

public static String getMimeTypeFromContentType(String name) {
  if (COMMON_CONTENT_TYPES.contains(name)) {
    return name;
  }
	Matcher m = CONTENT_TYPE_PATTERN_EXPLICIT_MIME_TYPE.matcher(name);
	if (!m.matches()) {
	  throw new IllegalArgumentException();
	}
	return m.group(1);
}


// 14.1
public static void parseAccept(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseAcceptCharset(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// Case insensitive
public static void parseAcceptEncoding(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseAcceptLanguage(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseAcceptRanges(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseAge(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseAllow(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.8
public static void parseAuthorization(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseCacheControl(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseConnection(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.11
public static void parseContentEncoding(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseContentLanguage(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseContentLength(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseContentLocation(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseContentMD5(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseContentRange(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.17
public static String[] parseContentType(String name) {
	Matcher m = CONTENT_TYPE_PATTERN.matcher(name);
	if (!m.matches()) {
	  throw new IllegalArgumentException();
	}
	ArrayList<String> result = new ArrayList<>();
	result.add(m.group(1));
	result.add(m.group(2));
	String params = m.group(3);
	if (params != null) {
		m = PARAMETER_PATTERN.matcher(m.group(3));
		while (m.find()) {
			result.add(m.group(1));
			String value = m.group(2);
			if (value != null) {
				result.add(value);
			} else {
				result.add(m.group(3).replace("\\\"", "\""));
			}
		}
	}
	return result.toArray(new String[0]);
}

public static void parseDate(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseETag(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.20
public static void parseExpect(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseExpires(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseFrom(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseHost(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.24
public static void parseIfMatch(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseIfModifiedSince(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseIfNoneMatch(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseIfRange(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseIfUnmodifiedSince(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseLastModified(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseLocation(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseMaxForwards(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.32
public static void parsePragma(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseProxyAuthenticate(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseProxyAuthorization(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseRange(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseReferer(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseRetryAfter(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseServer(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.39
public static void parseTE(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseTrailer(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseTransferEncoding(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseUpgrade(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseUserAgent(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseVary(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseVia(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

public static void parseWarning(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

// 14.47
public static void parseWwwAuthenticate(@SuppressWarnings("unused") String text)
{ throw new UnsupportedOperationException(); }

}
