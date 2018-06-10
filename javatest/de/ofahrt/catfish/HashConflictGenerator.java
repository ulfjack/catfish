package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.List;

public final class HashConflictGenerator {

  private static final long TWO_POW32 = 4294967296L;

  public static HashConflictGenerator using(String allowedChars) {
    return new HashConflictGenerator(allowedChars);
  }

  private final char[] chars;
  private final long target;
  private final int length;

  private HashConflictGenerator(char[] chars, long target, int length) {
    this.chars = chars;
    this.target = (target + TWO_POW32) % TWO_POW32;
    this.length = length;
  }

  private HashConflictGenerator(String allowedChars) {
    this(allowedChars.toCharArray(), 0, 0);
  }

  public HashConflictGenerator withHashCode(int newTarget) {
    return new HashConflictGenerator(chars, newTarget, length);
  }

  public HashConflictGenerator withLength(int newLength) {
    return new HashConflictGenerator(chars, target, newLength);
  }

  public HashConflictGenerator withTarget(String targetString) {
    return new HashConflictGenerator(chars, targetString.hashCode(), targetString.length());
  }

  private void generateConflictingStrings(StringListener listener, char[] sofar, int index,
      long hashCode) {
//    String s = new String(sofar, 0, index);
//    System.out.println(s + " ACTUAL_HASH=" + s.hashCode() + " HASH=" + (int) hashCode + " MAXDIST=" + maxDist + " TARGET=" + (int) target);
    if (index == sofar.length) {
      String test = new String(sofar);
      if (test.hashCode() == (int) target) {
//        System.out.println(test + " " + test.hashCode());
        listener.add(test);
      }
      return;
    }
    long min = hashCode;
    long max = hashCode;
    for (int i = index; i < sofar.length; i++) {
      min = min * 31 + chars[0];
      max = max * 31 + chars[chars.length - 1];
    }
//    System.out.println("MIN   =" + min);
//    System.out.println("MAX   =" + max);
//    System.out.println("TARGET=" + target);
    long base = (min / TWO_POW32) * TWO_POW32;
    min -= base;
    max -= base;
//    System.out.println(min + " " + max);
    if ((min > target) && (max < TWO_POW32 + target)) {
      return;
    }
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      sofar[index] = c;
      generateConflictingStrings(listener, sofar, index + 1, (hashCode * 31 + c) % TWO_POW32);
    }
  }

  public void generateConflictingStrings(StringListener listener) {
    generateConflictingStrings(listener, new char[length], 0, 0);
  }

  public List<String> generateList(final int maxCount) {
    final List<String> result = new ArrayList<>();
    try {
      generateConflictingStrings(new HashConflictGenerator.StringListener() {
        @Override
        public void add(String s) {
          if ((maxCount != -1) && (result.size() + 1 > maxCount)) {
            throw new StopException();
          }
          result.add(s);
        }
      });
    } catch (StopException e) {
      // String generation stopped.
    }
    return result;
  }

  private static class StopException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  public static interface StringListener {
    void add(String s);
  }
}
