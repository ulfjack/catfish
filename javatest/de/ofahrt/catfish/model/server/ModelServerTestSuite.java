package de.ofahrt.catfish.model.server;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  CompressionPolicyTest.class,
  DenyUploadPolicyTest.class,
  KeepAlivePolicyTest.class,
  RequestActionTest.class,
  RequestOutcomeTest.class,
  RouterTest.class,
})
public class ModelServerTestSuite {}
