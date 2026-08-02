# HTTP Conformance Test Cases (CISPA AsiaCCS 2024)

106 rules extracted from HTTP specifications. Each test is marked as **REQUIREMENT** (MUST/MUST NOT), **RECOMMENDATION** (SHOULD/SHOULD NOT), or **ABNF** (grammar validation).

## Coverage cross-reference

The **Coverage** column links each rule to the Catfish test that asserts it, or records why there is
no test. **Keep it in sync when you add a test or a feature** — an untracked rule is how a pass over
this suite wrongly concludes "no coverage." Tests carry a matching `Conformance test #N` / `(#N)` tag
where practical; the count below is derived by auditing the tests, not just grepping tags (9 rules are
covered by tests that predate the tagging convention and are marked †).

Status counts: **67 covered** · **26 gaps** (7 hold by construction but are untested, 19 real holes) ·
**13 n/a** (feature absent or the application handler's responsibility).

Legend for the Coverage column:
- `<TestAlias>#method` — a test asserts this rule (see aliases below). `†` = covered but was untagged.
- `GAP` — no test **and** the behavior is not enforced (a real hole worth a spec/PR).
- `GAP·enforced` — behavior holds by construction (e.g. single-valued header map) but no test pins it.
- `n/a` — not applicable to this library; reason given.

Test path aliases (all under `javatest/de/ofahrt/catfish/`):
`RVT` = `model/HttpResponseValidatorTest.java` ·
`HPT` = `HttpParserTest.java` ·
`BIT` = `integration/BasicIntegrationTest.java` ·
`RGBT` = `http/HttpResponseGeneratorBufferedTest.java` ·
`CHT` = `integration/ConnectionHandlingTest.java` ·
`CIT` = `integration/CompressionIntegrationTest.java` ·
`OFT` = `OriginForwarderTest.java` ·
`HDT` = `model/HttpDateTest.java` ·
`CHST` = `CatfishHttpServerTest.java`

## HTTP (General)

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 1 | If a client sends both Upgrade and Expect 100-continue, server must send 100 first then 101 | REQUIREMENT | RFC 9110 §Upgrade | n/a — no HTTP/1.1 Upgrade-based protocol switch (h2 via ALPN/preface) |
| 2 | Reject messages with field values containing CR, LF or NUL (or replace with SP) | REQUIREMENT | RFC 9110 §Field Values | HPT#crInHeaderValue |
| 3 | Fields (headers + trailers) are not allowed to occur several times unless their definition allows it | REQUIREMENT | RFC 9110 §Field Order | RVT#contentLengthAndTransferEncodingThrows |
| 4 | No Content-Length header allowed for 1xx and 204 | REQUIREMENT | RFC 9110 §Content-Length | RVT#noContentWithContentLengthThrows |
| 5 | Send Upgrade header field with 426 response | REQUIREMENT | RFC 9110 §Upgrade | RVT#upgradeRequiredWithoutUpgradeThrows |
| 6 | Server that sends 101 must send an Upgrade header field | REQUIREMENT | RFC 9110 §Upgrade | RVT#switchingProtocolsWithoutUpgradeThrows |
| 7 | Server must not switch to a protocol not indicated by the client's Upgrade header | REQUIREMENT | RFC 9110 §Upgrade | n/a — server never performs Upgrade switching |
| 8 | If Content-Length is returned to HEAD request it must be the same as in GET | REQUIREMENT | RFC 9110 §Content-Length | BIT#headMatchesGet |
| 9 | If Content-Length returned for conditional GET (304), it must match normal GET (200) | REQUIREMENT | RFC 9110 §Content-Length | n/a — core does not auto-generate conditional 304s (handler's job) |

## HTTP Headers

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 10 | Reply with 400 to requests with bad hosts (missing, duplicate, or invalid Host header) | REQUIREMENT | RFC 9112 §Request-Target | HPT#duplicateHostReturns400 |
| 11 | No Content-Length header allowed for 2XX responses to CONNECT | REQUIREMENT | RFC 9110 §Content-Length | GAP·enforced — fixed 200 constant, `ConnectStage.java:53`; untested |
| 12 | No Transfer-Encoding header allowed for 2XX responses to CONNECT | REQUIREMENT | RFC 9112 §Transfer-Encoding | GAP·enforced — same constant `ConnectStage.java:53`; untested |
| 13 | No overly detailed Server header fields | RECOMMENDATION | RFC 9110 §Server | n/a — Catfish emits no Server header |
| 14 | A message with content should have a Content-Type header | RECOMMENDATION | RFC 9110 §Content-Type | GAP — validator checks CT format but never requires it for a body |
| 15 | STS directives must not appear more than once | REQUIREMENT | RFC 6797 §6.1 | GAP — `isValidSts` (`HttpResponseValidator.java:970`) accepts repeats |
| 16 | Only one STS header allowed | REQUIREMENT | RFC 6797 §7.1 | GAP·enforced — single-valued `HttpHeaders` map; untested |
| 17 | No STS header field for HTTP request over non-secure transport | REQUIREMENT | RFC 6797 §7.2 | GAP — `validate()` never checks transport before allowing STS |
| 18 | Date header field required for all status codes except 1xx and 5xx | REQUIREMENT | RFC 9110 §Date | BIT#dateHeaderPresentOnCoreResponse † |
| 19 | No Transfer-Encoding header allowed with 1xx, 204 | REQUIREMENT | RFC 9112 §Transfer-Encoding | RVT#noContentWithContentLengthThrows |
| 20 | Transfer-Encoding must not be sent unless request indicates HTTP/1.1 or later | REQUIREMENT | RFC 9112 §Transfer-Encoding | GAP — chunked emitted without gating on version, `HttpResponseGeneratorStreamed.java:383` |
| 21 | max-age directive is required in STS header | REQUIREMENT | RFC 6797 §6.1.1 | RVT#stsWithMaxAgeDoesNotThrow |
| 22 | Upgrade-Insecure-Requests: redirect if encountered | RECOMMENDATION | Upgrade Insecure Requests | n/a — feature unimplemented |
| 23 | Upgrade-Insecure-Requests: include STS header in response | RECOMMENDATION | Upgrade Insecure Requests | n/a — feature unimplemented |
| 24 | Accept-Patch should appear where PATCH is supported | RECOMMENDATION | RFC 5789 §3.1 | n/a — no Accept-Patch / PATCH support |
| 25 | Server should send only one CSP header | RECOMMENDATION | CSP spec | GAP·enforced — single-valued `HttpHeaders` map; untested |
| 26 | Server should send only one CSP (Report Only) header | RECOMMENDATION | CSP spec | GAP·enforced — single-valued `HttpHeaders` map; untested |

## HTTP Methods

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 27 | Servers should reply with 501 for unknown request methods | RECOMMENDATION | RFC 9110 §Overview | BIT#unknownMethodReturns501 |
| 28 | Servers should reply with 405 when request method is not allowed for target resource | RECOMMENDATION | RFC 9110 §Overview | BIT#deleteNotAllowedReturns405 |
| 29 | No message body in HEAD response | REQUIREMENT | RFC 9110 §HEAD | RGBT#simpleWithBodyButSkipBody † |
| 30 | Status codes 206, 304, 416 are not allowed as answers to POST requests | REQUIREMENT | RFC 9110 §POST | RVT#postWith206Throws |
| 31 | Same header fields for HEAD and GET responses | RECOMMENDATION | RFC 9110 §HEAD | BIT#headMatchesGet |

## HTTP/1.1

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 32 | One CRLF in front of the request line should be allowed | RECOMMENDATION | RFC 9112 §Message Parsing | HPT#leadingCrlfIgnored |
| 33 | Reject messages with whitespace between start-line and first header field | REQUIREMENT | RFC 9112 §Message Parsing | GAP·enforced — 400 via `IncrementalHttpRequestParser.java:285`; no direct test |
| 34 | Reject (400) any message with whitespace between header field name and colon | REQUIREMENT | RFC 9112 §Field Line Parsing | HPT#badHeader † |
| 35 | Server should send "close" connection option in final response when client sends Connection: close | RECOMMENDATION | RFC 9112 §Tear-Down | CHT#connectionCloseRespectsHeader |
| 36 | Server must not generate bare CR (outside of content) | REQUIREMENT | RFC 9112 §Message Parsing | RGBT#responsesUseCrlfLineTerminators |

## HTTP/2

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 37 | 101 Switching Protocols not allowed in HTTP/2 | REQUIREMENT | RFC 9113 §Upgrade | GAP — handler-returned `:status` passed through, `Http2ServerStage.java:804` |
| 38 | Field name must not contain non-visible ASCII, SP, or uppercase characters | REQUIREMENT | RFC 9113 §Field Validity | GAP — no h2 field-name char validation (`HpackDecoder`, `Http2ServerStage.java:447`) |
| 39 | Field name must not contain colon except for pseudo-header fields | REQUIREMENT | RFC 9113 §Field Validity | GAP — embedded colon not rejected, `Http2ServerStage.java:438` |
| 40 | Field value must not contain zero value, line feed, or carriage return | REQUIREMENT | RFC 9113 §Field Validity | GAP — no h2 field-value validation |
| 41 | Field value must not start or end with whitespace | REQUIREMENT | RFC 9113 §Field Validity | GAP — no h2 field-value validation |

## Cache-Control

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 42 | No token form in no-cache directive (must use quoted-string) | RECOMMENDATION | RFC 9111 §no-cache | GAP — `isValidCacheControl` accepts token form for no-cache |
| 43 | No token form in private directive (must use quoted-string) | RECOMMENDATION | RFC 9111 §private | GAP — `isValidCacheControl` accepts token form for private |
| 44 | No quoted string in max-age directive (must use token) | REQUIREMENT | RFC 9111 §max-age | RVT#cacheControlNoCacheDoesNotThrow |
| 45 | No quoted string in s-maxage directive (must use token) | REQUIREMENT | RFC 9111 §s-maxage | RVT#cacheControlNoCacheDoesNotThrow |

## HTTP Cookies

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 46 | Cookies should follow the cookie grammar | ABNF | RFC 6265 §4.1.1 | GAP — session cookie unvalidated, `bridge/RequestImpl.java:546` |
| 47 | Servers should not produce two attributes with same name in same Set-Cookie string | RECOMMENDATION | RFC 6265 §4.1.1 | GAP — no Set-Cookie attribute-dedup check |
| 48 | Should not include more than one Set-Cookie with same cookie-name in same response | RECOMMENDATION | RFC 6265 §4.1.1 | GAP·enforced — `bridge/ResponseImpl.java:78` single-valued map; untested |
| 49 | Cookies should use IMF-fixdate (four-digit year) | RECOMMENDATION | RFC 6265 §4.1.1 | n/a — session cookie carries no Expires/date attribute |

## Status Codes

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 50 | 300 Multiple Choices: should have a Location header field | RECOMMENDATION | RFC 9110 §300 | RVT#multipleChoicesWithoutLocationThrows |
| 51 | 300 Multiple Choices: response should not be empty | RECOMMENDATION | RFC 9110 §300 | GAP — non-empty-body for 300 not enforced |
| 52 | 301 Moved Permanently: should have a Location header field | RECOMMENDATION | RFC 9110 §301 | RVT#movedPermanentlyWithoutLocationThrows |
| 53 | 302 Found: should have a Location header field | RECOMMENDATION | RFC 9110 §302 | RVT#foundWithoutLocationThrows † |
| 54 | 303 See Other: should have a Location header field | RECOMMENDATION | RFC 9110 §303 | RVT#seeOtherWithoutLocationThrows † |
| 55 | 307 Temporary Redirect: should have a Location header field | RECOMMENDATION | RFC 9110 §307 | RVT#temporaryRedirectWithoutLocationThrows † |
| 56 | 308 Permanent Redirect: should have a Location header field | RECOMMENDATION | RFC 9110 §308 | RVT#permanentRedirectWithoutLocationThrows |
| 57 | 413 Content Too Large: should send Retry-After if temporary | RECOMMENDATION | RFC 9110 §413 | n/a — "if temporary" is handler knowledge; 413 is a static response |
| 58 | 415 Unsupported Media Type: should have Accept-Encoding or Accept header | RECOMMENDATION | RFC 9110 §415 | GAP — `StandardResponses.java:60` omits Accept/Accept-Encoding |
| 59 | 416 Range Not Satisfiable: should have Content-Range header | RECOMMENDATION | RFC 9110 §416 | GAP — `StandardResponses.java:62` omits Content-Range |
| 60 | 204 No Content: must not have content after header section | REQUIREMENT | RFC 9110 §204 | RVT#noContentWithBodyThrows |
| 61 | 205 Reset Content: no content allowed | REQUIREMENT | RFC 9110 §205 | CHST#resetContentContainsNoBody; RVT#noContentWithBodyThrows |
| 62 | 206 Partial Content: must have Content-Range or multipart/byteranges Content-Type | REQUIREMENT | RFC 9110 §206 | RVT#partialContentWithoutContentRangeOrMultipartThrows |
| 63 | 206 Partial Content: Content-Range and multipart/byteranges not allowed simultaneously | REQUIREMENT | RFC 9110 §206 | RVT#partialContentWithoutContentRangeOrMultipartThrows |
| 64 | 206 Partial Content: must include Date, Cache-Control, ETag, Expires, Content-Location, Vary (if in 200) | REQUIREMENT | RFC 9110 §206 | n/a — depends on the origin 200; handler's responsibility |
| 65 | 304 Not Modified: no content allowed | REQUIREMENT | RFC 9110 §304 | RVT#noContentWithBodyThrows |
| 66 | 304 Not Modified: must include same headers as 200 (Date, Cache-Control, ETag, Expires, Content-Location, Vary) | REQUIREMENT | RFC 9110 §304 | n/a — depends on the origin 200; handler's responsibility |
| 67 | 401 Unauthorized: must send WWW-Authenticate header with challenge | REQUIREMENT | RFC 9110 §401 | RVT#unauthorizedWithoutWwwAuthenticateThrows |
| 68 | 405 Method Not Allowed: must include Allow header field | REQUIREMENT | RFC 9110 §405 | RVT#methodNotAllowedWithoutAllowThrows |
| 69 | 407 Proxy Authentication Required: must send Proxy-Authenticate header | REQUIREMENT | RFC 9110 §407 | RVT#proxyAuthRequiredWithoutProxyAuthenticateThrows |

## ABNF / Grammar Checks (response header syntax validation)

| # | Test | Level | Source | Coverage |
|---|------|-------|--------|----------|
| 70 | Cross-Origin-Embedder-Policy follows COEP ABNF | ABNF | WHATWG HTML | RVT#coepRequireCorpDoesNotThrow |
| 71 | Cross-Origin-Resource-Policy follows CORP ABNF | ABNF | Fetch spec | RVT#corpSameSiteDoesNotThrow |
| 72 | Content-Security-Policy follows CSP ABNF | ABNF | CSP spec | RVT#cspDefaultSrcSelfDoesNotThrow |
| 73 | Content-Security-Policy-Report-Only follows CSP-RO ABNF | ABNF | CSP spec | RVT#cspReportOnlyDefaultSrcSelfDoesNotThrow |
| 74 | Permissions-Policy follows structured dictionary ABNF | ABNF | Permissions Policy spec | RVT#permissionsPolicyAllowAllDoesNotThrow |
| 75 | X-Content-Type-Options must be "nosniff" | ABNF | Fetch spec | RVT#xContentTypeOptionsInvalidThrows |
| 76 | Strict-Transport-Security follows STS ABNF | ABNF | RFC 6797 §6.1 | RVT#stsWithMaxAgeDoesNotThrow |
| 77 | X-Frame-Options follows XFO ABNF (DENY / SAMEORIGIN only) | ABNF | WHATWG HTML | RVT#xFrameOptionsDenyDoesNotThrow |
| 78 | Cross-Origin-Opener-Policy follows COOP possible values | ABNF | WHATWG HTML | RVT#coopSameOriginDoesNotThrow |
| 79 | Access-Control-Allow-Origin follows ACAO ABNF | ABNF | Fetch spec | RVT#accessControlAllowOriginWildcardDoesNotThrow |
| 80 | Access-Control-Allow-Credentials must be literal "true" | ABNF | Fetch spec | RVT#accessControlAllowCredentialsTrueDoesNotThrow |
| 81 | Access-Control-Expose-Headers follows ACEH ABNF | ABNF | Fetch spec | RVT#accessControlExposeHeadersWildcardDoesNotThrow |
| 82 | Access-Control-Max-Age is a non-negative integer | ABNF | Fetch spec | RVT#accessControlMaxAgeZeroDoesNotThrow |
| 83 | Access-Control-Allow-Methods follows ACAM ABNF | ABNF | Fetch spec | RVT#accessControlAllowMethodsWildcardDoesNotThrow |
| 84 | Access-Control-Allow-Headers follows ACAH ABNF | ABNF | Fetch spec | RVT#accessControlAllowHeadersWildcardDoesNotThrow |
| 85 | Age is a non-negative integer | ABNF | RFC 9111 §Age | RVT#ageZeroDoesNotThrow |
| 86 | Cache-Control follows grammar | ABNF | RFC 9111 §Cache-Control | RVT#cacheControlNoCacheDoesNotThrow |
| 87 | Server header follows grammar | ABNF | RFC 9110 §Server | GAP — no `isValidServer`; Catfish emits none, handler value unvalidated |
| 88 | Retry-After is HTTP-date or non-negative integer | ABNF | RFC 9110 §Retry-After | RVT#retryAfterIntegerDoesNotThrow |
| 89 | Proxy-Authorization follows grammar | ABNF | RFC 9110 §Proxy-Authorization | n/a — request header; Catfish never emits it |
| 90 | Location is a valid URI-reference | ABNF | RFC 9110 §Location | RVT#locationValidUriDoesNotThrow |
| 91 | Last-Modified is a valid HTTP-date | ABNF | RFC 9110 §Last-Modified | RVT#lastModifiedValidDateDoesNotThrow |
| 92 | Expires is a valid HTTP-date | ABNF | RFC 9111 §Expires | RVT#expiresValidDateDoesNotThrow |
| 93 | ETag follows entity-tag grammar | ABNF | RFC 9110 §ETag | RVT#etagStrongDoesNotThrow |
| 94 | Date is a valid HTTP-date | ABNF | RFC 9110 §Date | HDT#formatHttpSpecExample † |
| 95 | Content-Type follows media-type grammar | ABNF | RFC 9110 §Content-Type | RVT#contentTypeSimpleDoesNotThrow |
| 96 | Content-Range follows grammar | ABNF | RFC 9110 §Content-Range | RVT#contentRangeWithCompleteLengthDoesNotThrow |
| 97 | Content-Length is a non-negative integer | ABNF | RFC 9110 §Content-Length | RVT#contentLengthZeroDoesNotThrow |
| 98 | Content-Language follows language-tag grammar | ABNF | RFC 9110 §Content-Language | RVT#contentLanguageSimpleDoesNotThrow |
| 99 | Content-Encoding follows content-coding grammar | ABNF | RFC 9110 §Content-Encoding | CIT#withGzipAcceptEncoding_correctHeadersAndDecompressibleBody † |
| 100 | Connection follows token list grammar | ABNF | RFC 9110 §Connection | OFT#keepAliveFalse_setsConnectionClose † |
| 101 | Allow follows method list grammar | ABNF | RFC 9110 §Allow | RVT#allowValidMethodsDoesNotThrow |
| 102 | Accept-Ranges follows grammar | ABNF | RFC 9110 §Accept-Ranges | GAP — no `isValidAcceptRanges` (Catfish emits none) |
| 103 | Accept-Encoding follows grammar | ABNF | RFC 9110 §Accept-Encoding | n/a — request header; Catfish never emits it |
| 104 | Accept-Patch follows media-type list grammar | ABNF | RFC 5789 §3.1 | GAP — no `isValidAcceptPatch` (Catfish emits none) |
| 105 | Transfer-Encoding follows grammar | ABNF | RFC 9112 §Transfer-Encoding | RVT#transferEncodingChunkedDoesNotThrow |
| 106 | Vary follows grammar | ABNF | RFC 9110 §Vary | RVT#varyStarDoesNotThrow |
