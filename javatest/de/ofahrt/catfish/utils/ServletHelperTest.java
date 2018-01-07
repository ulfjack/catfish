package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;

import org.junit.Test;

public class ServletHelperTest
{

private static FormData parseFormData(String contentType, String content) throws Exception
{
	byte[] data = content.replace("\n", "\r\n").getBytes("ISO-8859-1");
	return ServletHelper.parseFormData(data.length, new ByteArrayInputStream(data), contentType);
}

public static boolean isTokenCharacter(char c)
{ return ServletHelper.isTokenCharacter(c); }

@Test
public void validMimeType()
{ assertTrue(ServletHelper.isValidContentType("text/html")); }

@Test
public void validMimeTypeWithParameter()
{ assertTrue(ServletHelper.isValidContentType("text/html;charset=UTF-8")); }

@Test
public void validMimeTypeWithParameterAndSpace()
{ assertTrue(ServletHelper.isValidContentType("text/html; charset=UTF-8")); }

@Test
public void validMimeTypeWithQuotedParameter()
{ assertTrue(ServletHelper.isValidContentType("text/html; charset=\"UTF-8\"")); }

@Test
public void validMimeTypeWithQuotedParameterWithQuotedPair()
{ assertTrue(ServletHelper.isValidContentType("text/html; charset=\"\\\"\"")); }

@Test
public void invalidMimeTypeWithWhitespace1()
{ assertFalse(ServletHelper.isValidContentType("text /html")); }

@Test
public void invalidMimeTypeWithWhitespace2()
{ assertFalse(ServletHelper.isValidContentType("text/ html")); }

@Test
public void invalidMimeTypeWithWhitespace3()
{ assertFalse(ServletHelper.isValidContentType("text/html; charset =utf-8")); }

@Test
public void invalidMimeTypeWithWhitespace4()
{ assertFalse(ServletHelper.isValidContentType("text/html; charset= utf-8")); }

@Test
public void parseContentType()
{
	assertArrayEquals(new String[] { "text", "html" },
			ServletHelper.parseContentType("text/html"));
}

@Test
public void parseContentTypeWithParameter()
{
	assertArrayEquals(new String[] { "text", "html", "charset", "utf-8" },
			ServletHelper.parseContentType("text/html;charset=utf-8"));
}

@Test
public void parseContentTypeWithParameterWithQuotedEscape()
{
	assertArrayEquals(new String[] { "text", "html", "charset", "\"" },
			ServletHelper.parseContentType("text/html;charset=\"\\\"\""));
}

@Test
public void parseContentTypeWithTwoParameters()
{
	assertArrayEquals(new String[] { "text", "html", "a", "b", "c", "d" },
			ServletHelper.parseContentType("text/html;a=b;c=d"));
}

@Test
public void parseFormDataSimple() throws Exception
{
	FormData formData = parseFormData("multipart/form-data; boundary=abc",
		  "--abc\n"
		+ "Content-Disposition: form-data; name=\"a\"\n"
		+ "\n"
		+ "b\n"
		+ "--abc--\n");
	assertEquals(0, formData.files.size());
	assertEquals(1, formData.data.size());
	assertEquals("b", formData.data.get("a"));
}

}
