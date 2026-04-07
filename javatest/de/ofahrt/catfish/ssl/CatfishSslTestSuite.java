package de.ofahrt.catfish.ssl;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  Asn1ParserTest.class,
  OpensslCertificateAuthorityTest.class,
  SSLContextFactoryTest.class,
})
public class CatfishSslTestSuite {}
