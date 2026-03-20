# HTTP Conformance Test Cases (CISPA AsiaCCS 2024)

106 rules extracted from HTTP specifications. Each test is marked as **REQUIREMENT** (MUST/MUST NOT), **RECOMMENDATION** (SHOULD/SHOULD NOT), or **ABNF** (grammar validation).

## HTTP (General)

| # | Test | Level | Source |
|---|------|-------|--------|
| 1 | If a client sends both Upgrade and Expect 100-continue, server must send 100 first then 101 | REQUIREMENT | RFC 9110 §Upgrade |
| 2 | Reject messages with field values containing CR, LF or NUL (or replace with SP) | REQUIREMENT | RFC 9110 §Field Values |
| 3 | Fields (headers + trailers) are not allowed to occur several times unless their definition allows it | REQUIREMENT | RFC 9110 §Field Order |
| 4 | No Content-Length header allowed for 1xx and 204 | REQUIREMENT | RFC 9110 §Content-Length |
| 5 | Send Upgrade header field with 426 response | REQUIREMENT | RFC 9110 §Upgrade |
| 6 | Server that sends 101 must send an Upgrade header field | REQUIREMENT | RFC 9110 §Upgrade |
| 7 | Server must not switch to a protocol not indicated by the client's Upgrade header | REQUIREMENT | RFC 9110 §Upgrade |
| 8 | If Content-Length is returned to HEAD request it must be the same as in GET | REQUIREMENT | RFC 9110 §Content-Length |
| 9 | If Content-Length returned for conditional GET (304), it must match normal GET (200) | REQUIREMENT | RFC 9110 §Content-Length |

## HTTP Headers

| # | Test | Level | Source |
|---|------|-------|--------|
| 10 | Reply with 400 to requests with bad hosts (missing, duplicate, or invalid Host header) | REQUIREMENT | RFC 9112 §Request-Target |
| 11 | No Content-Length header allowed for 2XX responses to CONNECT | REQUIREMENT | RFC 9110 §Content-Length |
| 12 | No Transfer-Encoding header allowed for 2XX responses to CONNECT | REQUIREMENT | RFC 9112 §Transfer-Encoding |
| 13 | No overly detailed Server header fields | RECOMMENDATION | RFC 9110 §Server |
| 14 | A message with content should have a Content-Type header | RECOMMENDATION | RFC 9110 §Content-Type |
| 15 | STS directives must not appear more than once | REQUIREMENT | RFC 6797 §6.1 |
| 16 | Only one STS header allowed | REQUIREMENT | RFC 6797 §7.1 |
| 17 | No STS header field for HTTP request over non-secure transport | REQUIREMENT | RFC 6797 §7.2 |
| 18 | Date header field required for all status codes except 1xx and 5xx | REQUIREMENT | RFC 9110 §Date |
| 19 | No Transfer-Encoding header allowed with 1xx, 204 | REQUIREMENT | RFC 9112 §Transfer-Encoding |
| 20 | Transfer-Encoding must not be sent unless request indicates HTTP/1.1 or later | REQUIREMENT | RFC 9112 §Transfer-Encoding |
| 21 | max-age directive is required in STS header | REQUIREMENT | RFC 6797 §6.1.1 |
| 22 | Upgrade-Insecure-Requests: redirect if encountered | RECOMMENDATION | Upgrade Insecure Requests |
| 23 | Upgrade-Insecure-Requests: include STS header in response | RECOMMENDATION | Upgrade Insecure Requests |
| 24 | Accept-Patch should appear where PATCH is supported | RECOMMENDATION | RFC 5789 §3.1 |
| 25 | Server should send only one CSP header | RECOMMENDATION | CSP spec |
| 26 | Server should send only one CSP (Report Only) header | RECOMMENDATION | CSP spec |

## HTTP Methods

| # | Test | Level | Source |
|---|------|-------|--------|
| 27 | Servers should reply with 501 for unknown request methods | RECOMMENDATION | RFC 9110 §Overview |
| 28 | Servers should reply with 405 when request method is not allowed for target resource | RECOMMENDATION | RFC 9110 §Overview |
| 29 | No message body in HEAD response | REQUIREMENT | RFC 9110 §HEAD |
| 30 | Status codes 206, 304, 416 are not allowed as answers to POST requests | REQUIREMENT | RFC 9110 §POST |
| 31 | Same header fields for HEAD and GET responses | RECOMMENDATION | RFC 9110 §HEAD |

## HTTP/1.1

| # | Test | Level | Source |
|---|------|-------|--------|
| 32 | One CRLF in front of the request line should be allowed | RECOMMENDATION | RFC 9112 §Message Parsing |
| 33 | Reject messages with whitespace between start-line and first header field | REQUIREMENT | RFC 9112 §Message Parsing |
| 34 | Reject (400) any message with whitespace between header field name and colon | REQUIREMENT | RFC 9112 §Field Line Parsing |
| 35 | Server should send "close" connection option in final response when client sends Connection: close | RECOMMENDATION | RFC 9112 §Tear-Down |
| 36 | Server must not generate bare CR (outside of content) | REQUIREMENT | RFC 9112 §Message Parsing |

## HTTP/2

| # | Test | Level | Source |
|---|------|-------|--------|
| 37 | 101 Switching Protocols not allowed in HTTP/2 | REQUIREMENT | RFC 9113 §Upgrade |
| 38 | Field name must not contain non-visible ASCII, SP, or uppercase characters | REQUIREMENT | RFC 9113 §Field Validity |
| 39 | Field name must not contain colon except for pseudo-header fields | REQUIREMENT | RFC 9113 §Field Validity |
| 40 | Field value must not contain zero value, line feed, or carriage return | REQUIREMENT | RFC 9113 §Field Validity |
| 41 | Field value must not start or end with whitespace | REQUIREMENT | RFC 9113 §Field Validity |

## Cache-Control

| # | Test | Level | Source |
|---|------|-------|--------|
| 42 | No token form in no-cache directive (must use quoted-string) | RECOMMENDATION | RFC 9111 §no-cache |
| 43 | No token form in private directive (must use quoted-string) | RECOMMENDATION | RFC 9111 §private |
| 44 | No quoted string in max-age directive (must use token) | REQUIREMENT | RFC 9111 §max-age |
| 45 | No quoted string in s-maxage directive (must use token) | REQUIREMENT | RFC 9111 §s-maxage |

## HTTP Cookies

| # | Test | Level | Source |
|---|------|-------|--------|
| 46 | Cookies should follow the cookie grammar | ABNF | RFC 6265 §4.1.1 |
| 47 | Servers should not produce two attributes with same name in same Set-Cookie string | RECOMMENDATION | RFC 6265 §4.1.1 |
| 48 | Should not include more than one Set-Cookie with same cookie-name in same response | RECOMMENDATION | RFC 6265 §4.1.1 |
| 49 | Cookies should use IMF-fixdate (four-digit year) | RECOMMENDATION | RFC 6265 §4.1.1 |

## Status Codes

| # | Test | Level | Source |
|---|------|-------|--------|
| 50 | 300 Multiple Choices: should have a Location header field | RECOMMENDATION | RFC 9110 §300 |
| 51 | 300 Multiple Choices: response should not be empty | RECOMMENDATION | RFC 9110 §300 |
| 52 | 301 Moved Permanently: should have a Location header field | RECOMMENDATION | RFC 9110 §301 |
| 53 | 302 Found: should have a Location header field | RECOMMENDATION | RFC 9110 §302 |
| 54 | 303 See Other: should have a Location header field | RECOMMENDATION | RFC 9110 §303 |
| 55 | 307 Temporary Redirect: should have a Location header field | RECOMMENDATION | RFC 9110 §307 |
| 56 | 308 Permanent Redirect: should have a Location header field | RECOMMENDATION | RFC 9110 §308 |
| 57 | 413 Content Too Large: should send Retry-After if temporary | RECOMMENDATION | RFC 9110 §413 |
| 58 | 415 Unsupported Media Type: should have Accept-Encoding or Accept header | RECOMMENDATION | RFC 9110 §415 |
| 59 | 416 Range Not Satisfiable: should have Content-Range header | RECOMMENDATION | RFC 9110 §416 |
| 60 | 204 No Content: must not have content after header section | REQUIREMENT | RFC 9110 §204 |
| 61 | 205 Reset Content: no content allowed | REQUIREMENT | RFC 9110 §205 |
| 62 | 206 Partial Content: must have Content-Range or multipart/byteranges Content-Type | REQUIREMENT | RFC 9110 §206 |
| 63 | 206 Partial Content: Content-Range and multipart/byteranges not allowed simultaneously | REQUIREMENT | RFC 9110 §206 |
| 64 | 206 Partial Content: must include Date, Cache-Control, ETag, Expires, Content-Location, Vary (if in 200) | REQUIREMENT | RFC 9110 §206 |
| 65 | 304 Not Modified: no content allowed | REQUIREMENT | RFC 9110 §304 |
| 66 | 304 Not Modified: must include same headers as 200 (Date, Cache-Control, ETag, Expires, Content-Location, Vary) | REQUIREMENT | RFC 9110 §304 |
| 67 | 401 Unauthorized: must send WWW-Authenticate header with challenge | REQUIREMENT | RFC 9110 §401 |
| 68 | 405 Method Not Allowed: must include Allow header field | REQUIREMENT | RFC 9110 §405 |
| 69 | 407 Proxy Authentication Required: must send Proxy-Authenticate header | REQUIREMENT | RFC 9110 §407 |

## ABNF / Grammar Checks (response header syntax validation)

| # | Test | Level | Source |
|---|------|-------|--------|
| 70 | Cross-Origin-Embedder-Policy follows COEP ABNF | ABNF | WHATWG HTML |
| 71 | Cross-Origin-Resource-Policy follows CORP ABNF | ABNF | Fetch spec |
| 72 | Content-Security-Policy follows CSP ABNF | ABNF | CSP spec |
| 73 | Content-Security-Policy-Report-Only follows CSP-RO ABNF | ABNF | CSP spec |
| 74 | Permissions-Policy follows structured dictionary ABNF | ABNF | Permissions Policy spec |
| 75 | X-Content-Type-Options must be "nosniff" | ABNF | Fetch spec |
| 76 | Strict-Transport-Security follows STS ABNF | ABNF | RFC 6797 §6.1 |
| 77 | X-Frame-Options follows XFO ABNF (DENY / SAMEORIGIN only) | ABNF | WHATWG HTML |
| 78 | Cross-Origin-Opener-Policy follows COOP possible values | ABNF | WHATWG HTML |
| 79 | Access-Control-Allow-Origin follows ACAO ABNF | ABNF | Fetch spec |
| 80 | Access-Control-Allow-Credentials must be literal "true" | ABNF | Fetch spec |
| 81 | Access-Control-Expose-Headers follows ACEH ABNF | ABNF | Fetch spec |
| 82 | Access-Control-Max-Age is a non-negative integer | ABNF | Fetch spec |
| 83 | Access-Control-Allow-Methods follows ACAM ABNF | ABNF | Fetch spec |
| 84 | Access-Control-Allow-Headers follows ACAH ABNF | ABNF | Fetch spec |
| 85 | Age is a non-negative integer | ABNF | RFC 9111 §Age |
| 86 | Cache-Control follows grammar | ABNF | RFC 9111 §Cache-Control |
| 87 | Server header follows grammar | ABNF | RFC 9110 §Server |
| 88 | Retry-After is HTTP-date or non-negative integer | ABNF | RFC 9110 §Retry-After |
| 89 | Proxy-Authorization follows grammar | ABNF | RFC 9110 §Proxy-Authorization |
| 90 | Location is a valid URI-reference | ABNF | RFC 9110 §Location |
| 91 | Last-Modified is a valid HTTP-date | ABNF | RFC 9110 §Last-Modified |
| 92 | Expires is a valid HTTP-date | ABNF | RFC 9111 §Expires |
| 93 | ETag follows entity-tag grammar | ABNF | RFC 9110 §ETag |
| 94 | Date is a valid HTTP-date | ABNF | RFC 9110 §Date |
| 95 | Content-Type follows media-type grammar | ABNF | RFC 9110 §Content-Type |
| 96 | Content-Range follows grammar | ABNF | RFC 9110 §Content-Range |
| 97 | Content-Length is a non-negative integer | ABNF | RFC 9110 §Content-Length |
| 98 | Content-Language follows language-tag grammar | ABNF | RFC 9110 §Content-Language |
| 99 | Content-Encoding follows content-coding grammar | ABNF | RFC 9110 §Content-Encoding |
| 100 | Connection follows token list grammar | ABNF | RFC 9110 §Connection |
| 101 | Allow follows method list grammar | ABNF | RFC 9110 §Allow |
| 102 | Accept-Ranges follows grammar | ABNF | RFC 9110 §Accept-Ranges |
| 103 | Accept-Encoding follows grammar | ABNF | RFC 9110 §Accept-Encoding |
| 104 | Accept-Patch follows media-type list grammar | ABNF | RFC 5789 §3.1 |
| 105 | Transfer-Encoding follows grammar | ABNF | RFC 9112 §Transfer-Encoding |
| 106 | Vary follows grammar | ABNF | RFC 9110 §Vary |
