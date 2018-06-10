package de.ofahrt.catfish.bridge;

import java.util.Map;
import java.util.TreeMap;

public class FormData {

  public Map<String,FileData> files = new TreeMap<>();
  public Map<String,String> data = new TreeMap<>();
}