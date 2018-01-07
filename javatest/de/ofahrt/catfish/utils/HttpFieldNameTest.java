package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpFieldNameTest
{

@Test
public void returnIdentical()
{ assertEquals(HttpFieldName.ACCEPT, HttpFieldName.canonicalize(HttpFieldName.ACCEPT)); }

@Test
public void returnCorrectCapitalization()
{ assertEquals(HttpFieldName.ACCEPT, HttpFieldName.canonicalize("aCCEPT")); }

@Test
public void returnLowerCaseForUnknown()
{ assertEquals("x-catfish-unknown", HttpFieldName.canonicalize("X-CATFISH-UNkNOWN")); }

@Test
public void areEqualSimple()
{ assertTrue(HttpFieldName.areEqual(HttpFieldName.ACCEPT, HttpFieldName.ACCEPT)); }

@Test
public void areEqualCapitalization()
{ assertTrue(HttpFieldName.areEqual(HttpFieldName.ACCEPT, "aCCEpT")); }

@Test
public void areEqualNotEqual()
{ assertFalse(HttpFieldName.areEqual(HttpFieldName.ACCEPT, "aCxEpT")); }

@Test
public void areEqualForUnknown()
{ assertTrue(HttpFieldName.areEqual("x-catfish-unknown", "X-CATFISH-UNkNOWN")); }

}
