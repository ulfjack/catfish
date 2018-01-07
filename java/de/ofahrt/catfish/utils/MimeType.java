package de.ofahrt.catfish.utils;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MimeType implements Serializable
{

private static final long serialVersionUID = 3902904509160688751L;

private static final Pattern MIME_PATTERN = Pattern.compile("(\\w+)/(\\w+)");
private static final ConcurrentHashMap<String,MimeType> cache = new ConcurrentHashMap<String,MimeType>(30);

public static MimeType getInstance(String primary, String subtype)
{
	StringBuilder builder = new StringBuilder(primary.length()+subtype.length()+1);
	builder.append(primary).append('/').append(subtype);
	String key = builder.toString();
	MimeType mimetype = cache.get(key);
	if (mimetype == null)
	{
		mimetype = new MimeType(primary, subtype);
		MimeType m = cache.putIfAbsent(key, mimetype);
		if (m != null) mimetype = m;
	}
	return mimetype;
}

public static MimeType parseMimeType(String type)
{
	MimeType result = cache.get(type);
	if (result != null) return result;
	Matcher m = MIME_PATTERN.matcher(type);
	if (m.matches())
		return getInstance(m.group(1), m.group(2));
	throw new IllegalArgumentException("not a valid mime-type: \""+type+"\"");
}


public static final MimeType APPLICATION_JAVASCRIPT    = getInstance("application", "javascript");
public static final MimeType APPLICATION_JSON          = getInstance("application", "json");
public static final MimeType APPLICATION_OCTET_STREAM  = getInstance("application", "octet-stream");
public static final MimeType APPLICATION_OGG           = getInstance("application", "ogg");
public static final MimeType APPLICATION_PDF           = getInstance("application", "pdf");
public static final MimeType APPLICATION_POSTSCRIPT    = getInstance("application", "postscript");
public static final MimeType APPLICATION_XHTML_AND_XML = getInstance("application", "xhtml+xml");
public static final MimeType APPLICATION_XML           = getInstance("application", "xml");
public static final MimeType APPLICATION_XML_DTD       = getInstance("application", "xml-dtd");
public static final MimeType APPLICATION_ZIP           = getInstance("application", "zip");

public static final MimeType IMAGE_GIF  = getInstance("image", "gif");
public static final MimeType IMAGE_JPEG = getInstance("image", "jpeg");
public static final MimeType IMAGE_PNG  = getInstance("image", "png");
public static final MimeType IMAGE_TIFF = getInstance("image", "tiff");
public static final MimeType IMAGE_SVG  = getInstance("image", "svg+xml");

public static final MimeType MODEL_MESH = getInstance("model", "mesh");
public static final MimeType MODEL_VRML = getInstance("model", "vrml");

public static final MimeType TEXT_CALENDAR = getInstance("text", "calendar");
public static final MimeType TEXT_CSS      = getInstance("text", "css");
public static final MimeType TEXT_CSV      = getInstance("text", "csv");
public static final MimeType TEXT_HTML     = getInstance("text", "html");
public static final MimeType TEXT_PLAIN    = getInstance("text", "plain");
public static final MimeType TEXT_RICHTEXT = getInstance("text", "richtext");
public static final MimeType TEXT_RTF      = getInstance("text", "rtf");
public static final MimeType TEXT_XML      = getInstance("text", "xml");

public static final MimeType VIDEO_MPEG      = getInstance("video", "mpeg");
public static final MimeType VIDEO_QUICKTIME = getInstance("video", "quicktime");

private final String primary;
private final String subtype;

private transient volatile int cachedHashCode = 0;

public MimeType(String primary, String subtype)
{
	this.primary = primary.intern();
	this.subtype = subtype.intern();
}

public MimeType(String primary)
{ this(primary, ""); }

public String getPrimaryType()
{ return primary; }

public String getSubType()
{ return subtype; }

public boolean isText()
{ return "text".equals(primary); }

@Override
public int hashCode()
{
	if (cachedHashCode == 0)
		cachedHashCode = primary.hashCode() * 313 + subtype.hashCode();
	return cachedHashCode;
}

@Override
public boolean equals(Object o)
{
	if (o == this) return true;
	if (!(o instanceof MimeType)) return false;
	MimeType other = (MimeType) o;
	return (primary == other.primary) && (subtype == other.subtype);
}

@Override
public String toString()
{
	StringBuilder result = new StringBuilder(primary);
	result.append('/').append(subtype);
	return result.toString();
}

}
