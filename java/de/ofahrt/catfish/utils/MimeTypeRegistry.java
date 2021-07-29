package de.ofahrt.catfish.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MimeTypeRegistry {
  public static final MimeType DEFAULT_MIMETYPE = MimeType.APPLICATION_OCTET_STREAM;

  private static final Map<String,MimeType> EXTENSION_TABLE = getExtensionTable();

  public static MimeType guessFromExtension(String fileextension) {
  	MimeType result = EXTENSION_TABLE.get(fileextension.toLowerCase(Locale.US));
  	if (result == null) {
  	  return DEFAULT_MIMETYPE;
  	}
  	return result;
  }

  public static MimeType guessFromFilename(String filename) {
  	String extension = filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.US);
  	MimeType result = EXTENSION_TABLE.get(extension);
  	if (result == null) {
  	  return DEFAULT_MIMETYPE;
  	}
  	return result;
  }

  private static Map<String, MimeType> getExtensionTable() {
  	Map<String,MimeType> result = new HashMap<>();
  	result.put("ez",    MimeType.getInstance("application", "andrew-inset"));
    result.put("hqx",   MimeType.getInstance("application", "mac-binhex40"));
    result.put("cpt",   MimeType.getInstance("application", "mac-compactpro"));
    result.put("doc",   MimeType.getInstance("application", "msword"));
    result.put("bin",   MimeType.getInstance("application", "octet-stream"));
    result.put("dms",   MimeType.getInstance("application", "octet-stream"));
    result.put("lha",   MimeType.getInstance("application", "octet-stream"));
    result.put("lzh",   MimeType.getInstance("application", "octet-stream"));
    result.put("exe",   MimeType.getInstance("application", "octet-stream"));
    result.put("class", MimeType.getInstance("application", "octet-stream"));
    result.put("so",    MimeType.getInstance("application", "octet-stream"));
    result.put("dll",   MimeType.getInstance("application", "octet-stream"));
    result.put("oda",   MimeType.getInstance("application", "oda"));
    result.put("pdf",   MimeType.getInstance("application", "pdf"));
    result.put("ai",    MimeType.getInstance("application", "postscript"));
    result.put("eps",   MimeType.getInstance("application", "postscript"));
    result.put("ps",    MimeType.getInstance("application", "postscript"));
    result.put("rtf",   MimeType.getInstance("application", "rtf"));
    result.put("smi",   MimeType.getInstance("application", "smil"));
    result.put("smil",  MimeType.getInstance("application", "smil"));
    result.put("mif",   MimeType.getInstance("application", "vnd.mif"));
    result.put("xls",   MimeType.getInstance("application", "vnd.ms-excel"));
    result.put("ppt",   MimeType.getInstance("application", "vnd.ms-powerpoint"));
    result.put("sic",   MimeType.getInstance("application", "vnd.wap.sic"));
    result.put("slc",   MimeType.getInstance("application", "vnd.wap.slc"));
    result.put("wbxml", MimeType.getInstance("application", "vnd.wap.wbxml"));
    result.put("wmlc",  MimeType.getInstance("application", "vnd.wap.wmlc"));
    result.put("wmlsc", MimeType.getInstance("application", "vnd.wap.wmlscriptc"));
    result.put("bcpio", MimeType.getInstance("application", "x-bcpio"));
    result.put("bz2",   MimeType.getInstance("application", "x-bzip2"));
    result.put("vcd",   MimeType.getInstance("application", "x-cdlink"));
    result.put("pgn",   MimeType.getInstance("application", "x-chess-pgn"));
    result.put("cpio",  MimeType.getInstance("application", "x-cpio"));
    result.put("csh",   MimeType.getInstance("application", "x-csh"));
    result.put("dcr",   MimeType.getInstance("application", "x-director"));
    result.put("dir",   MimeType.getInstance("application", "x-director"));
    result.put("dxr",   MimeType.getInstance("application", "x-director"));
    result.put("dvi",   MimeType.getInstance("application", "x-dvi"));
    result.put("spl",   MimeType.getInstance("application", "x-futuresplash"));
    result.put("gtar",  MimeType.getInstance("application", "x-gtar"));
    result.put("gz",    MimeType.getInstance("application", "x-gzip"));
    result.put("tgz",   MimeType.getInstance("application", "x-gzip"));
    result.put("hdf",   MimeType.getInstance("application", "x-hdf"));
    result.put("js",    MimeType.getInstance("application", "x-javascript"));
    result.put("kwd",   MimeType.getInstance("application", "x-kword"));
    result.put("kwt",   MimeType.getInstance("application", "x-kword"));
    result.put("ksp",   MimeType.getInstance("application", "x-kspread"));
    result.put("kpr",   MimeType.getInstance("application", "x-kpresenter"));
    result.put("kpt",   MimeType.getInstance("application", "x-kpresenter"));
    result.put("chrt",  MimeType.getInstance("application", "x-kchart"));
    result.put("kil",   MimeType.getInstance("application", "x-killustrator"));
  	result.put("rdf",   MimeType.getInstance("application", "xml"));
    result.put("skp",   MimeType.getInstance("application", "x-koan"));
    result.put("skd",   MimeType.getInstance("application", "x-koan"));
    result.put("skt",   MimeType.getInstance("application", "x-koan"));
    result.put("skm",   MimeType.getInstance("application", "x-koan"));
    result.put("latex", MimeType.getInstance("application", "x-latex"));
    result.put("nc",    MimeType.getInstance("application", "x-netcdf"));
    result.put("cdf",   MimeType.getInstance("application", "x-netcdf"));
    result.put("ogg",   MimeType.getInstance("application", "x-ogg"));
    result.put("rpm",   MimeType.getInstance("application", "x-rpm"));
    result.put("sh",    MimeType.getInstance("application", "x-sh"));
    result.put("shar",  MimeType.getInstance("application", "x-shar"));
    result.put("swf",   MimeType.getInstance("application", "x-shockwave-flash"));
    result.put("sit",   MimeType.getInstance("application", "x-stuffit"));
    result.put("sv4cpio", MimeType.getInstance("application", "x-sv4cpio"));
    result.put("sv4crc",  MimeType.getInstance("application", "x-sv4crc"));
    result.put("tar",   MimeType.getInstance("application", "x-tar"));
    result.put("tcl",   MimeType.getInstance("application", "x-tcl"));
    result.put("tex",   MimeType.getInstance("application", "x-tex"));
    result.put("texinfo", MimeType.getInstance("application", "x-texinfo"));
    result.put("texi",  MimeType.getInstance("application", "x-texinfo"));
    result.put("t",     MimeType.getInstance("application", "x-troff"));
    result.put("tr",    MimeType.getInstance("application", "x-troff"));
    result.put("roff",  MimeType.getInstance("application", "x-troff"));
    result.put("man",   MimeType.getInstance("application", "x-troff-man"));
    result.put("me",    MimeType.getInstance("application", "x-troff-me"));
    result.put("ms",    MimeType.getInstance("application", "x-troff-ms"));
    result.put("ustar", MimeType.getInstance("application", "x-ustar"));
    result.put("src",   MimeType.getInstance("application", "x-wais-source"));
    result.put("xhtml", MimeType.getInstance("application", "xhtml+xml"));
    result.put("xht",   MimeType.getInstance("application", "xhtml+xml"));
  	result.put("xul",   MimeType.getInstance("application", "vnd.mozilla.xul+xml"));
    result.put("zip",   MimeType.getInstance("application", "zip"));
    
    result.put("jar", MimeType.getInstance("application", "java-archive"));
    
    result.put("au",   MimeType.getInstance("audio", "basic"));
    result.put("snd",  MimeType.getInstance("audio", "basic"));
    result.put("mid",  MimeType.getInstance("audio", "midi"));
    result.put("midi", MimeType.getInstance("audio", "midi"));
    result.put("kar",  MimeType.getInstance("audio", "midi"));
    result.put("mpga", MimeType.getInstance("audio", "mpeg"));
    result.put("mp2",  MimeType.getInstance("audio", "mpeg"));
    result.put("mp3",  MimeType.getInstance("audio", "mpeg"));
    result.put("aif",  MimeType.getInstance("audio", "x-aiff"));
    result.put("aiff", MimeType.getInstance("audio", "x-aiff"));
    result.put("aifc", MimeType.getInstance("audio", "x-aiff"));
    result.put("m3u",  MimeType.getInstance("audio", "x-mpegurl"));
    result.put("ram",  MimeType.getInstance("audio", "x-pn-realaudio"));
    result.put("rm",   MimeType.getInstance("audio", "x-pn-realaudio"));
    result.put("ra",   MimeType.getInstance("audio", "x-realaudio"));
    result.put("wav",  MimeType.getInstance("audio", "x-wav"));
    
    result.put("pdb",  MimeType.getInstance("chemical", "x-pdb"));
    result.put("xyz",  MimeType.getInstance("chemical", "x-xyz"));

    result.put("woff2", MimeType.getInstance("font", "woff2"));
    
    result.put("bmp",  MimeType.getInstance("image", "bmp"));
    result.put("gif",  MimeType.getInstance("image", "gif"));
    result.put("ico",  MimeType.getInstance("image", "x-icon"));
    result.put("ief",  MimeType.getInstance("image", "ief"));
    result.put("jpeg", MimeType.getInstance("image", "jpeg"));
    result.put("jpg",  MimeType.getInstance("image", "jpeg"));
    result.put("jpe",  MimeType.getInstance("image", "jpeg"));
    result.put("png",  MimeType.getInstance("image", "png"));
    result.put("tiff", MimeType.getInstance("image", "tiff"));
    result.put("tif",  MimeType.getInstance("image", "tiff"));
    result.put("djvu", MimeType.getInstance("image", "vnd.djvu"));
    result.put("djv",  MimeType.getInstance("image", "vnd.djvu"));
    result.put("wbmp", MimeType.getInstance("image", "vnd.wap.wbmp"));
    result.put("ras",  MimeType.getInstance("image", "x-cmu-raster"));
    result.put("pnm",  MimeType.getInstance("image", "x-portable-anymap"));
    result.put("pbm",  MimeType.getInstance("image", "x-portable-bitmap"));
    result.put("pgm",  MimeType.getInstance("image", "x-portable-graymap"));
    result.put("ppm",  MimeType.getInstance("image", "x-portable-pixmap"));
    result.put("rgb",  MimeType.getInstance("image", "x-rgb"));
    result.put("svg",  MimeType.getInstance("image", "svg+xml"));
    result.put("xbm",  MimeType.getInstance("image", "x-xbitmap"));
    result.put("xpm",  MimeType.getInstance("image", "x-xpixmap"));
    result.put("xwd",  MimeType.getInstance("image", "x-xwindowdump"));
    
    result.put("igs",  MimeType.getInstance("model", "iges"));
    result.put("iges", MimeType.getInstance("model", "iges"));
    result.put("msh",  MimeType.getInstance("model", "mesh"));
    result.put("mesh", MimeType.getInstance("model", "mesh"));
    result.put("silo", MimeType.getInstance("model", "mesh"));
    result.put("wrl",  MimeType.getInstance("model", "vrml"));
    result.put("vrml", MimeType.getInstance("model", "vrml"));
    
    result.put("js",   MimeType.getInstance("text", "javascript"));
    result.put("css",  MimeType.getInstance("text", "css"));
    result.put("html", MimeType.getInstance("text", "html"));
    result.put("htm",  MimeType.getInstance("text", "html"));
    result.put("asc",  MimeType.getInstance("text", "plain"));
    result.put("txt",  MimeType.getInstance("text", "plain"));
    result.put("rtx",  MimeType.getInstance("text", "richtext"));
    result.put("rtf",  MimeType.getInstance("text", "rtf"));
    result.put("sgml", MimeType.getInstance("text", "sgml"));
    result.put("sgm",  MimeType.getInstance("text", "sgml"));
    result.put("tsv",  MimeType.getInstance("text", "tab-separated-values"));
    result.put("si",   MimeType.getInstance("text", "vnd.wap.si"));
    result.put("sl",   MimeType.getInstance("text", "vnd.wap.sl"));
    result.put("wml",  MimeType.getInstance("text", "vnd.wap.wml"));
    result.put("wmls", MimeType.getInstance("text", "vnd.wap.wmlscript"));
    result.put("etx",  MimeType.getInstance("text", "x-setext"));
    result.put("xml",  MimeType.getInstance("text", "xml"));
    result.put("xsl",  MimeType.getInstance("text", "xml"));
    
    result.put("mpeg", MimeType.getInstance("video", "mpeg"));
    result.put("mpg",  MimeType.getInstance("video", "mpeg"));
    result.put("mpe",  MimeType.getInstance("video", "mpeg"));
    result.put("qt",   MimeType.getInstance("video", "quicktime"));
    result.put("mov",  MimeType.getInstance("video", "quicktime"));
    result.put("mxu",  MimeType.getInstance("video", "vnd.mpegurl"));
    result.put("avi",  MimeType.getInstance("video", "x-msvideo"));
    result.put("movie", MimeType.getInstance("video", "x-sgi-movie"));
    
    result.put("ice",  MimeType.getInstance("x-conference", "x-cooltalk"));
    return Collections.unmodifiableMap(result);
  }
}
