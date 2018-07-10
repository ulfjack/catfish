package de.ofahrt.catfish.model;

public interface StandardResponses {
  // 200
  public static final HttpResponse OK = new PreconstructedResponse(HttpStatusCode.OK);
  public static final HttpResponse NO_CONTENT = new PreconstructedResponse(HttpStatusCode.NO_CONTENT);

  // 300
  public static final HttpResponse NOT_MODIFIED = new PreconstructedResponse(HttpStatusCode.NOT_MODIFIED); // 304

  // 400
  public static final HttpResponse BAD_REQUEST = new PreconstructedResponse(HttpStatusCode.BAD_REQUEST);
  public static final HttpResponse UNAUTHORIZED = new PreconstructedResponse(HttpStatusCode.UNAUTHORIZED); // 401
  public static final HttpResponse PAYMENT_REQUIRED = new PreconstructedResponse(HttpStatusCode.PAYMENT_REQUIRED); // 402
  public static final HttpResponse FORBIDDEN = new PreconstructedResponse(HttpStatusCode.FORBIDDEN); // 403
  public static final HttpResponse NOT_FOUND = new PreconstructedResponse(HttpStatusCode.NOT_FOUND); // 404
  // TODO: According to the spec, a list of allowed methods must be provided.
  public static final HttpResponse METHOD_NOT_ALLOWED = new PreconstructedResponse(HttpStatusCode.METHOD_NOT_ALLOWED); // 405
  public static final HttpResponse NOT_ACCEPTABLE = new PreconstructedResponse(HttpStatusCode.NOT_ACCEPTABLE); // 406
  public static final HttpResponse PROXY_AUTH_REQUIRED = new PreconstructedResponse(HttpStatusCode.PROXY_AUTH_REQUIRED); // 407
  public static final HttpResponse REQUEST_TIMEOUT = new PreconstructedResponse(HttpStatusCode.REQUEST_TIMEOUT); // 408
  public static final HttpResponse CONFLICT = new PreconstructedResponse(HttpStatusCode.CONFLICT); // 409
  public static final HttpResponse GONE = new PreconstructedResponse(HttpStatusCode.GONE); // 410
  public static final HttpResponse LENGTH_REQUIRED = new PreconstructedResponse(HttpStatusCode.LENGTH_REQUIRED); // 411
  public static final HttpResponse PRECONDITION_FAILED = new PreconstructedResponse(HttpStatusCode.PRECONDITION_FAILED); // 412
  public static final HttpResponse PAYLOAD_TOO_LARGE = new PreconstructedResponse(HttpStatusCode.PAYLOAD_TOO_LARGE); // 413
  public static final HttpResponse URI_TOO_LONG = new PreconstructedResponse(HttpStatusCode.URI_TOO_LONG); // 414
  public static final HttpResponse UNSUPPORTED_MEDIA_TYPE = new PreconstructedResponse(HttpStatusCode.UNSUPPORTED_MEDIA_TYPE); // 415
  public static final HttpResponse RANGE_NOT_SATISFIABLE = new PreconstructedResponse(HttpStatusCode.RANGE_NOT_SATISFIABLE); // 416
  public static final HttpResponse EXPECTATION_FAILED = new PreconstructedResponse(HttpStatusCode.EXPECTATION_FAILED); // 417
  public static final HttpResponse UPGRADE_REQUIRED = new PreconstructedResponse(HttpStatusCode.UPGRADE_REQUIRED); // 426

  // 500
  public static final HttpResponse INTERNAL_SERVER_ERROR = new PreconstructedResponse(HttpStatusCode.INTERNAL_SERVER_ERROR); // 500
  public static final HttpResponse NOT_IMPLEMENTED = new PreconstructedResponse(HttpStatusCode.NOT_IMPLEMENTED); // 501
  public static final HttpResponse SERVICE_UNAVAILABLE = new PreconstructedResponse(HttpStatusCode.SERVICE_UNAVAILABLE); // 503

  public static HttpResponse forInternalServerError(Throwable throwable) {
    return throwable == null
        ? INTERNAL_SERVER_ERROR
        : InternalServerErrorResponse.create(throwable);
  }

  public static HttpResponse movedPermanentlyTo(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.MOVED_PERMANENTLY, destinationUrl); // 301
  }

  public static HttpResponse foundAt(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.FOUND, destinationUrl); // 302
  }

  public static HttpResponse temporaryRedirectTo(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.TEMPORARY_REDIRECT, destinationUrl); // 307
  }
}
