package de.ofahrt.catfish.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

public final class UuidGenerator {
  private final SecureRandom rand;
  private final AtomicLong numbers = new AtomicLong();

  public UuidGenerator() {
    this.rand = new SecureRandom();
  }

  private String checksum(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(data);
      byte[] temp = digest.digest();
      return Base64.encode(temp);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  public String generateID() {
    long time = System.nanoTime();
    long number = numbers.incrementAndGet();
    // 64 bytes = 512 bits; longer then the output but that shouldn't be a problem.
    byte[] temp = new byte[64];
    rand.nextBytes(temp);
    // We add in the current nano time and a number that is incremented on every request, in case
    // the random number generator is bad for some reason.
    temp[0] = (byte) (time >>> 0);
    temp[1] = (byte) (time >>> 8);
    temp[2] = (byte) (time >>> 16);
    temp[3] = (byte) (time >>> 24);
    temp[4] = (byte) (number >>> 0);
    temp[5] = (byte) (number >>> 8);
    temp[6] = (byte) (number >>> 16);
    temp[7] = (byte) (number >>> 24);
    return checksum(temp);
  }
}
