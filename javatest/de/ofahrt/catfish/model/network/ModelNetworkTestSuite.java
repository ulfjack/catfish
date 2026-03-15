package de.ofahrt.catfish.model.network;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  ConnectionTest.class,
  NetworkEventListenerTest.class,
})
public class ModelNetworkTestSuite {}
