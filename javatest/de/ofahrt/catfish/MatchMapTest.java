package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class MatchMapTest {

  @Test
  public void testSimple() {
  	MatchMap<Object> map = new MatchMap.Builder<>().build();
  	assertEquals(0, map.size());
  }

  @Test
  public void testPut() {
  	MatchMap<Object> map = new MatchMap.Builder<>()
  	    .put("*.txt", new Object())
  	    .build();
  	assertEquals(1, map.size());
  }

  @Test
  public void testFindPrefix() {
  	Object o = new Object();
  	MatchMap<Object> map = new MatchMap.Builder<>()
        .put("*.txt", o)
        .build();
  	assertSame(o, map.find("a.txt"));
  	assertNull(map.find("a.pdf"));
  }

  @Test
  public void testFindSuffix() {
  	Object o = new Object();
  	MatchMap<Object> map = new MatchMap.Builder<>()
  	   .put("/a*", o)
  	   .build();
  	assertSame(o, map.find("a.txt"));
  	assertNull(map.find("b.txt"));
  }

  @Test
  public void testFindExact() {
  	Object o = new Object();
  	MatchMap<Object> map = new MatchMap.Builder<>()
  	   .put("/a.txt", o)
  	   .build();
  	assertSame(o, map.find("a.txt"));
  	assertNull(map.find("b.txt"));
  	assertNull(map.find("a.pdf"));
  }

  @Test(expected=IllegalArgumentException.class)
  public void exactEntryMustStartWithSlash() {
  	new MatchMap.Builder<>()
  	    .put("a.txt", new Object());
  }

  @Test(expected=IllegalArgumentException.class)
  public void valueMustNotBeNull() {
  	new MatchMap.Builder<>()
      	.put("a*", null);
  }
}
