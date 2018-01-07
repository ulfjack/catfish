package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class DirectoryTest {

  @Test
  public void empty() {
  	Directory directory = new Directory.Builder().build();
  	assertNotNull(directory);
  	assertEquals("", directory.getName());
  	assertEquals(0, directory.getSubDirectories().size());
  	assertEquals(0, directory.getFilters().size());
  }

  @Test
  public void singleDirectory() {
  	Directory directory = new Directory.Builder().enter("a").build();
  	assertNotNull(directory);
  	assertEquals("", directory.getName());
  	assertEquals(1, directory.getSubDirectories().size());
  	assertEquals(0, directory.getFilters().size());
  	Directory sub = directory.getDirectory("a");
  	assertNotNull(sub);
  	assertEquals(0, sub.getSubDirectories().size());
  	assertEquals(0, sub.getFilters().size());
  }
}
