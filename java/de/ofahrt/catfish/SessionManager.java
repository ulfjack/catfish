package de.ofahrt.catfish;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import javax.servlet.http.HttpSession;

final class SessionManager {

	// package private for testing
	static class SessionEntry implements Comparable<SessionEntry> {
		final long entryNumber;
		final SessionImpl session;
		long nextTimeOut;
		SessionEntry(long entryNumber, SessionImpl session) {
			this.entryNumber = entryNumber;
			this.session = session;
			this.nextTimeOut = session.getTimeOut();
		}
		@Override
		public int compareTo(SessionEntry entry) {
			if (entry == this) return 0;
			if (nextTimeOut < entry.nextTimeOut) return -1;
			if (nextTimeOut > entry.nextTimeOut) return 1;
			if (entryNumber < entry.entryNumber) return -1;
			if (entryNumber > entry.entryNumber) return 1;
			throw new IllegalStateException("Ugh!");
		}
		@Override
		public boolean equals(Object other) {
			return other == this;
		}
		@Override
		public int hashCode() {
			return System.identityHashCode(this);
		}
	}

  private static final long DEFAULT_COOKIE_VALIDITY = 60 * 60 * 1000; // 1 Hour

  private final Clock clock;
  private long entryNumber = 0;
  private final HashMap<String,SessionEntry> sessions = new HashMap<>();
  private final TreeSet<SessionEntry> timeOutMap = new TreeSet<>();

  SessionManager(Clock clock) {
  	this.clock = clock;
  }

  public SessionManager() {
    this(new Clock.SystemClock());
  }

  private void checkTimeout(long time) {
  	boolean tryAgain = true;
  	while (tryAgain && (timeOutMap.size() != 0)) {
  		tryAgain = false;
  		SessionEntry entry = timeOutMap.first();
  		if (time > entry.nextTimeOut) {
  			sessions.remove(entry.session.getId());
  			timeOutMap.remove(entry);
  			tryAgain = true;
  		}
  	}
  }

  public synchronized HttpSession getSession(String id) {
  	long time = clock.currentTimeMillis();
  	checkTimeout(time);
	
  	SessionEntry result = sessions.get(id);
  	if (result == null) {
  		id = UUID.randomUUID().toString();
  		result = new SessionEntry(entryNumber++, new SessionImpl(id, time, DEFAULT_COOKIE_VALIDITY));
  		sessions.put(id, result);
  	} else {
  		timeOutMap.remove(result);
  		result.session.setOld();
  	}
  	result.session.access(time);
  	result.nextTimeOut = result.session.getTimeOut();
  	timeOutMap.add(result);
  	return result.session;
  }

  public synchronized void save(OutputStream dest) throws IOException {
  	ObjectOutputStream out = new ObjectOutputStream(dest);
  	List<SessionImpl> sessionsToSave = new ArrayList<>();
  	for (SessionEntry entry : sessions.values()) {
  		sessionsToSave.add(entry.session);
  	}
  	out.writeObject(sessionsToSave);
  	out.close();
  }

  @SuppressWarnings("unchecked")
  public synchronized void load(InputStream source) throws IOException {
  	ObjectInputStream in = null;
  	try {
  		in = new ObjectInputStream(source);
  		List<SessionImpl> loadedSessions = (List<SessionImpl>) in.readObject();
  		for (SessionImpl session : loadedSessions) {
  			SessionEntry entry = new SessionEntry(entryNumber++, session);
  			sessions.put(entry.session.getId(), entry);
  			timeOutMap.add(entry);
  		}
  	} catch (ClassCastException e) {
  	  throw new IOException(e);
  	} catch (ClassNotFoundException e) {
  	  throw new IOException(e);
  	} finally {
  		try {
  		  if (in != null) in.close();
  		} catch (IOException e) {
  		  // We can't really do anything about it after the fact.
  		}
  	}
  }
}
