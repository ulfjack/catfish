package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import javax.servlet.http.HttpSession;

import org.junit.Test;

import de.ofahrt.catfish.SessionManager.SessionEntry;

public class SessionManagerTest {

	static class ManualClock implements Clock {
		private long time = 0;

		public void advanceMillis(long millis) {
		  time += millis;
		}

		@Override
		public long currentTimeMillis() {
		  return time;
		}
	}

  @Test
  public void sameSessionOnRepeatedCalls() {
  	ManualClock clock = new ManualClock();
  	SessionManager manager = new SessionManager(clock);
  	HttpSession session = manager.getSession(null);
  	assertSame(session, manager.getSession(session.getId()));
  }

  @Test
  public void checkCorrectTimeOut() {
    final long cookieValidity = 60 * 60 * 1000; // same as SessionManager.DEFAULT_COOKIE_VALIDITY
  	ManualClock clock = new ManualClock();
  	SessionManager manager = new SessionManager(clock);
  	HttpSession session1 = manager.getSession(null);
    clock.advanceMillis(cookieValidity - 1);
    assertSame(session1, manager.getSession(session1.getId()));
  	clock.advanceMillis(cookieValidity + 1);
  	HttpSession session2 = manager.getSession(session1.getId());
  	assertNotSame(session1, session2);
  	assertFalse(session1.getId().equals(session2.getId()));
  }

  @Test
  public void checkSerialization() throws Exception {
  	Object o = new ArrayList<String>();
  	ManualClock clock = new ManualClock();
  	SessionManager manager = new SessionManager(clock);
  	HttpSession session1 = manager.getSession(null);
  	session1.setAttribute("me", o);
  	ByteArrayOutputStream out = new ByteArrayOutputStream();
  	manager.save(out);

  	manager = new SessionManager(clock);
  	manager.load(new ByteArrayInputStream(out.toByteArray()));
  	HttpSession session2 = manager.getSession(session1.getId());
  	assertEquals(session1.getId(), session2.getId());
  	assertEquals(o, session2.getAttribute("me"));
  }

  @Test(expected=IOException.class)
  public void checkFailedDeserialization() throws Exception {
  	ManualClock clock = new ManualClock();
  	ByteArrayOutputStream out = new ByteArrayOutputStream();
  	ObjectOutputStream oout = new ObjectOutputStream(out);
  	oout.writeObject(Integer.valueOf(1234));
  	oout.flush();
  	SessionManager manager = new SessionManager(clock);
  	manager.load(new ByteArrayInputStream(out.toByteArray()));
  }

  @Test
  public void sessionEntry() {
  	SessionEntry a = new SessionEntry(0, new SessionImpl("a", 0, 1));
  	SessionEntry b = new SessionEntry(1, new SessionImpl("b", 0, 1));
    SessionEntry c = new SessionEntry(1, new SessionImpl("b", 0, 2));
  	assertEquals(-1, a.compareTo(b));
  	assertEquals(1, b.compareTo(a));
    assertEquals(-1, a.compareTo(c));
    assertEquals(1, c.compareTo(a));
    assertEquals(-1, b.compareTo(c));
    assertEquals(1, c.compareTo(b));
    assertEquals(a, a);
    assertFalse(a.equals(b));
    assertEquals(System.identityHashCode(a), a.hashCode());
  }

  @Test
  public void sessionImpl() {
    SessionImpl session = new SessionImpl("a", 123, 1000);
    assertEquals(123, session.getCreationTime());
    assertEquals(123, session.getLastAccessedTime());
    assertEquals(1123, session.getTimeOut());
    assertEquals(1, session.getMaxInactiveInterval());
  }

  @Test
  public void sessionAttributes() {
    SessionImpl session = new SessionImpl("a", 123, 1000);
    Object o = "value";
    assertNull(session.getAttribute("notthere"));
    session.setAttribute("notthere", o);
    assertSame(o, session.getAttribute("notthere"));
    assertEquals(Arrays.asList("notthere"), CollectionsUtils.toList(session.getAttributeNames()));
    session.removeAttribute("notthere");
    assertNull(session.getAttribute("notthere"));
    assertEquals(Arrays.asList(), CollectionsUtils.toList(session.getAttributeNames()));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void sessionValues() {
    SessionImpl session = new SessionImpl("a", 123, 1000);
    Object o = "value";
    assertNull(session.getValue("notthere"));
    session.putValue("notthere", o);
    assertSame(o, session.getValue("notthere"));
    assertEquals(Arrays.asList("notthere"), Arrays.asList(session.getValueNames()));
    session.removeValue("notthere");
    assertNull(session.getValue("notthere"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void nonserializableAttribute() {
    new SessionImpl("a", 123, 1000).setAttribute("notserializable", new Object());
  }

  @Test
  public void sessionIsNew() {
    SessionImpl session = new SessionImpl("a", 123, 1000);
    assertTrue(session.isNew());
    session.setOld();
    assertFalse(session.isNew());
  }

  @Test
  public void sessionTimeout() {
    SessionImpl session = new SessionImpl("a", 123, 1000);
    session.setMaxInactiveInterval(3);
    assertEquals(3, session.getMaxInactiveInterval());
    assertEquals(123 + 3 * 1000, session.getTimeOut());
    session.setMaxInactiveInterval(-1);
    assertEquals(-1, session.getMaxInactiveInterval());
    assertEquals(Long.MAX_VALUE, session.getTimeOut());
  }
}
