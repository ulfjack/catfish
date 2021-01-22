package de.ofahrt.catfish.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class Enumerations {
  private static final Enumeration<Object> EMPTY = new AsEnumeration<>(Collections.<Object>emptyList());

  @SuppressWarnings("unchecked")
  public static <T> Enumeration<T> empty() {
    return (Enumeration<T>) EMPTY;
  }

  public static <T> Enumeration<T> of(final T value) {
    return new Enumeration<T>() {
      private boolean returned = false;

      @Override
      public boolean hasMoreElements() {
        return !returned;
      }

      @Override
      public T nextElement() {
        if (returned) {
          throw new NoSuchElementException();
        }
        returned = true;
        return value;
      }
    };
  }

  public static <T> Enumeration<T> of(Iterator<T> it) {
    return new AsEnumeration<>(it);
  }

  public static <T> Enumeration<T> of(Iterable<T> it) {
    return new AsEnumeration<>(it.iterator());
  }

  public static <T> Iterator<T> asIterator(Enumeration<T> e) {
    return new AsIterator<>(e);
  }

  public static String toString(Enumeration<?> e) {
    StringBuilder result = new StringBuilder();
    for (; e.hasMoreElements(); ) {
      if (result.length() != 0) {
        result.append(",");
      }
      result.append(e.nextElement());
    }
    return "[" + result.toString() + "]";
  }

  @SuppressWarnings("unchecked")
  public static <T> T[] toArray(Enumeration<?> headers, T[] result) {
    List<T> list = new ArrayList<>();
    for (Iterator<T> it = (Iterator<T>) asIterator(headers); it.hasNext(); ) {
      list.add(it.next());
    }
    return list.toArray(result);
  }

  final static class AsEnumeration<T> implements Enumeration<T> {
    private final Iterator<T> it;

    public AsEnumeration(Iterator<T> it) {
      this.it = it;
    }

    public AsEnumeration(Iterable<T> it) {
      this(it.iterator());
    }

    @Override
    public boolean hasMoreElements() {
      return it.hasNext();
    }

    @Override
    public T nextElement() {
      return it.next();
    }
  }

  final static class AsIterator<T> implements Iterator<T> {
    private final Enumeration<T> e;

    public AsIterator(Enumeration<T> e) {
      this.e = e;
    }

    @Override
    public boolean hasNext() {
      return e.hasMoreElements();
    }

    @Override
    public T next() {
      return e.nextElement();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}