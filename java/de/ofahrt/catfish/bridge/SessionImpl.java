package de.ofahrt.catfish.bridge;

import java.io.Serializable;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

@SuppressWarnings("deprecation")
final class SessionImpl implements HttpSession, Serializable {

  private static final long serialVersionUID = 2L;

  private final String id;
  private final long creationTime;
  private volatile long timeoutInterval;
  private volatile boolean isNew = true;
  private volatile long lastAccessTime;
  private final ConcurrentHashMap<String,Serializable> info = new ConcurrentHashMap<>();

  public SessionImpl(String id, long creationTime, long timeoutInterval) {
  	this.id = id;
  	this.creationTime = creationTime;
  	this.lastAccessTime = creationTime;
  	this.timeoutInterval = timeoutInterval;
  }

  long getTimeOut() {
    long temp = timeoutInterval;
    if (temp < 0) {
      return Long.MAX_VALUE;
    } else {
      return lastAccessTime + temp;
    }
  }

  void setOld() {
    isNew = false;
  }

  void access(long time) {
    lastAccessTime = time;
  }


  // Implementation of the Servlet API spec for HttpSession
  @Override
  public Object getAttribute(String name) {
    return info.get(name);
  }

  @Override
  public Enumeration<?> getAttributeNames() {
  	return Enumerations.of(info.keySet());
  }

  @Override
  public long getCreationTime() {
    return creationTime;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public long getLastAccessedTime() {
    return lastAccessTime;
  }

  @Override
  public int getMaxInactiveInterval() {
    if (timeoutInterval < 0) {
      return -1;
    }
    return (int) (timeoutInterval / 1000L);
  }

  @Override
  public ServletContext getServletContext() {
  	throw new UnsupportedOperationException();
  }

  @Deprecated
  @Override
  public HttpSessionContext getSessionContext() {
  	return new HttpSessionContext() {
  		@Override
  		public Enumeration<?> getIds() {
  			return Enumerations.empty();
  		}
  		@Override
  		public HttpSession getSession(String arg0) {
  		  return null;
  		}
  	};
  }

  @Deprecated
  @Override
  public Object getValue(String name) {
    return getAttribute(name);
  }

  @Deprecated
  @Override
  public String[] getValueNames() {
    return info.keySet().toArray(new String[0]);
  }

  @Override
  public void invalidate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @Deprecated
  @Override
  public void putValue(String name, Object value) {
    setAttribute(name, value);
  }

  @Override
  public void removeAttribute(String name) {
    info.remove(name);
  }

  @Deprecated
  @Override
  public void removeValue(String name) {
    info.remove(name);
  }

  @Override
  public void setAttribute(String name, Object value) {
  	if (!(value instanceof Serializable)) {
  		throw new IllegalArgumentException("value must be serializable!");
  	}
  	info.put(name, (Serializable) value);
  }

  @Override
  public void setMaxInactiveInterval(int interval) {
  	if (interval < 0) {
  		timeoutInterval = -1;
  	} else {
      timeoutInterval = 1000L * interval;
  	}
  }
}
