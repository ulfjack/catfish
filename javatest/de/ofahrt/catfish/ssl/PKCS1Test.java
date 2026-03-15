package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertEquals;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import org.junit.Test;

public class PKCS1Test {

  @Test
  public void roundTripWithGeneratedKey() throws Exception {
    // Java encodes RSA private keys in PKCS8 format (which wraps the PKCS1 RSAPrivateKey
    // structure), and PKCS1.parse() knows how to extract and parse that inner structure.
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(1024);
    KeyPair keyPair = gen.generateKeyPair();
    RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) keyPair.getPrivate();

    RSAPrivateCrtKeySpec spec = PKCS1.parse(privateKey.getEncoded());

    assertEquals(privateKey.getModulus(), spec.getModulus());
    assertEquals(privateKey.getPublicExponent(), spec.getPublicExponent());
    assertEquals(privateKey.getPrivateExponent(), spec.getPrivateExponent());
    assertEquals(privateKey.getPrimeP(), spec.getPrimeP());
    assertEquals(privateKey.getPrimeQ(), spec.getPrimeQ());
  }
}
