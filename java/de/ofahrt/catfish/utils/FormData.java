package de.ofahrt.catfish.utils;

import java.util.Map;
import java.util.TreeMap;

public class FormData {

  public Map<String,FileData> files = new TreeMap<String,FileData>();
  public Map<String,String> data = new TreeMap<String,String>();
}