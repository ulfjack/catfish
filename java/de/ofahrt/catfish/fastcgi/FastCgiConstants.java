package de.ofahrt.catfish.fastcgi;

final class FastCgiConstants {

  public static final int FCGI_VERSION_1 = 1;

//#define FCGI_BEGIN_REQUEST       1
//#define FCGI_ABORT_REQUEST       2
//#define FCGI_END_REQUEST         3
//#define FCGI_PARAMS              4
//#define FCGI_STDIN               5
//#define FCGI_STDOUT              6
//#define FCGI_STDERR              7
//#define FCGI_DATA                8
//#define FCGI_GET_VALUES          9
//#define FCGI_GET_VALUES_RESULT  10
//#define FCGI_UNKNOWN_TYPE       11
//#define FCGI_MAXTYPE (FCGI_UNKNOWN_TYPE)
  public static final int FCGI_BEGIN_REQUEST = 1;
  public static final int FCGI_ABORT_REQUEST = 2;
  public static final int FCGI_END_REQUEST = 3;
  public static final int FCGI_PARAMS = 4;
  public static final int FCGI_STDIN = 5;
  public static final int FCGI_STDOUT = 6;
  public static final int FCGI_GET_VALUES = 9;
  public static final int FCGI_GET_VALUES_RESULT = 10;

  public static final int FCGI_NULL_REQUEST_ID = 0;
}
