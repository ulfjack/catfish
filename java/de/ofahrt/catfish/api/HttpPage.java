package de.ofahrt.catfish.api;

import java.io.IOException;

public interface HttpPage {
  HttpResponse handleGet(HttpGetRequest request) throws IOException;
  HttpResponse handlePost(HttpPostRequest request) throws IOException;
}
