package de.ofahrt.catfish.internal;

import de.ofahrt.catfish.internal.network.NetworkEngineTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  CoreHelperTest.class,
  NetworkEngineTest.class,
})
public class CatfishInternalTestSuite {}
