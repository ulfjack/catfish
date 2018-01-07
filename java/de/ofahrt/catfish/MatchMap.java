package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.List;

final class MatchMap<T> {

	private static abstract class Entry<V> {
		protected final String key;
		protected final V value;
		public Entry(String key, V value) {
			this.key = key;
			this.value = value;
		}
		public abstract int getMatchLength(String needle);
	}
	
	private static class ExactEntry<V> extends Entry<V> {
		public ExactEntry(String key, V value) {
		  super(key.substring(1), value);
		}
		@Override
		public int getMatchLength(String needle) {
			if (key.equals(needle)) {
			  return key.length();
			}
			return -1;
		}
	}

	private static class SuffixEntry<V> extends Entry<V> {
		public SuffixEntry(String key, V value) {
		  super(key.substring(1), value);
		}
		@Override
		public int getMatchLength(String needle) {
			if (needle.endsWith(key)) {
			  return key.length();
			}
			return -1;
		}
	}
	
	private static class PrefixEntry<V> extends Entry<V> {
		public PrefixEntry(String key, V value) {
		  super(key.substring(1, key.length()-1), value);
		}
		@Override
		public int getMatchLength(String needle) {
			if (needle.startsWith(key)) {
			  return key.length();
			}
			return -1;
		}
	}

  private final ArrayList<Entry<T>> list;

  private MatchMap(List<Entry<T>> data) {
    this.list = new ArrayList<>(data);
  }

  public int size() {
    return list.size();
  }

  public T find(String name) {
  	int bestValue = -1;
  	Entry<T> bestResult = null;
  	for (Entry<T> entry : list) {
  		int newValue = entry.getMatchLength(name);
  		if (newValue > bestValue) {
  			bestValue = newValue;
  			bestResult = entry;
  		}
  	}
  	return bestResult != null ? bestResult.value : null;
  }

  public static class Builder<T> {
    private final ArrayList<Entry<T>> list = new ArrayList<>();

    public MatchMap<T> build() {
      return new MatchMap<>(list);
    }

    public Builder<T> put(String pathSpec, T value) {
      if (value == null) {
        throw new IllegalArgumentException();
      }
      Entry<T> entry;
      if (pathSpec.startsWith("*")) {
        entry = new SuffixEntry<>(pathSpec, value);
      } else {
        if (!pathSpec.startsWith("/"))
          throw new IllegalArgumentException("pathSpec must start with /");
        if (pathSpec.endsWith("*"))
          entry = new PrefixEntry<>(pathSpec, value);
        else
          entry = new ExactEntry<>(pathSpec, value);
      }
      list.add(entry);
      return this;
    }
  }
}
