package de.ofahrt.catfish.fastcgi;

/** FCGI record type constants (FastCGI 1.0 spec §8). */
final class FastCgiConstants {

  private FastCgiConstants() {}

  static final int FCGI_VERSION_1 = 1;
  static final int FCGI_BEGIN_REQUEST = 1;
  static final int FCGI_END_REQUEST = 3;
  static final int FCGI_PARAMS = 4;
  static final int FCGI_STDIN = 5;
  static final int FCGI_STDOUT = 6;
  static final int FCGI_STDERR = 7;
}
