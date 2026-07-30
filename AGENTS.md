# AGENTS.md — Working on Catfish with an AI agent

This file is the entry point for AI coding agents (and humans pairing with them) working on
Catfish. It tells you how the repo is laid out, how to build and test, the conventions the code
holds itself to, and — most importantly — the **spec-driven development flow** we use to turn a
feature request into a reviewed, tested change.

If you read only one thing: **non-trivial changes start with a spec in `docs/features/`, not with
code.** See [Spec-driven development](docs/development/spec-driven-development.md).

## What Catfish is

Catfish is a Java 21+ library for embedding an HTTP/1.1 and HTTP/2 server into a JVM application. It
favours explicit, low-level control over framework magic: no annotation scanning, no dependency
injection, no servlet container (there is an optional servlet bridge). See [README.md](README.md)
for the user-facing overview and quick start.

## Build & test

The build is [Bazel](https://bazel.build/) with bzlmod. Common commands:

| Task | Command |
|---|---|
| Build everything | `bazel build //...` |
| Run all tests | `bazel test //...` |
| Run one test target | `bazel test //javatest/de/ofahrt/catfish/http:http` |
| Format (rewrite in place) | `bazel run //:format` |
| Format check (CI gate) | `bazel run //:format.check` |
| Static analysis | `bazel run //:pmd` |
| Copy-paste detection | `bazel run //:cpd` |
| Coverage (LCOV) | `bazel coverage //javatest/...` then `bazel run //:coverage_report` |

A change is not done until `bazel test //...` is green and `bazel run //:format.check` passes.

## Conventions the code holds itself to

- **Null-safety is enforced, not aspirational.** NullAway runs as an `ERROR`-level check
  (`.bazelrc`) over `de.ofahrt.catfish`. Annotate nullable references with
  `org.jspecify.annotations.Nullable`; everything else is non-null by default. A build that leaks a
  possible null fails.
- **Formatting is mechanical.** google-java-format owns whitespace and import order. Don't
  hand-format; run `bazel run //:format`.
- **Tests live in `javatest/`** mirroring the `java/` package layout, and are aggregated into
  per-package suites (e.g. `CatfishTestSuite`, `HttpTestSuite`). New tests should join the relevant
  suite so they run under the umbrella target.
- **The network core is a non-blocking pipeline of `Stage`s.** Socket I/O runs on selector
  threads; application handlers run on a separate `Executor`. A stage must never block the NIO
  thread. If you're touching `SslServerStage`, `HttpServerStage`, `Http2ServerStage`, or the
  `internal/network` package, read the flow-control comments first — `ConnectionControl` and the
  `encourageReads`/`encourageWrites` handshake are load-bearing.
- **Public API changes are deliberate.** The `de.ofahrt.catfish` and `de.ofahrt.catfish.model`
  packages are what applications compile against. Prefer additive changes; document them in the
  README.
- **Small, reviewable commits with explanatory messages.** Explain *why*, not just *what* — the
  existing history (`git log`) is the style guide.

## Repository layout

```
java/de/ofahrt/catfish/           core server, endpoints, stages
  http/                           HTTP/1.1 parsing & response generation
  http2/                          HTTP/2 framing, HPACK, stream handling
  ssl/                            TLS context, SNI, MITM CA
  upload/                         request-body / multipart / chunked parsing
  model/                          public request/response/header model
    server/                       handler-facing SPI (HttpHandler, policies, ...)
    network/                      connection & listener model
  bridge/                         optional servlet bridge
  internal/network/               NIO engine & Stage abstraction
javatest/...                      tests, mirroring the java/ layout
docs/features/                    numbered, reviewed feature specs (status: ready+)
docs/proposals/                   pre-ready thinking with open questions (not linted)
docs/development/                 process docs, incl. spec-driven-development.md
```

## Spec-driven development, in one paragraph

For anything beyond a trivial fix: write a short spec in `docs/features/NNNN-title.md` from the
[template](docs/features/TEMPLATE.md). It has YAML frontmatter (`id`, `title`, `status`, `owner`,
`architecture_refs`) and fixed sections — Summary, Goals, Non-Goals, Background, Design, **Security
Considerations**, Decisions, Open Questions, Acceptance Criteria, Implementation Plan, Notes. Get it
reviewed *before* writing code — a wrong spec is cheap to fix, a wrong implementation is not. A
committed `docs/features/` spec is **`ready`**: its *Open Questions* must be empty (each resolved
into a **Decision** with rationale), which the pre-commit `lint-specs` gate enforces. Pre-ready
thinking with unresolved questions lives in `docs/proposals/` until promoted. Then implement against
the acceptance criteria, keep the spec updated if reality pushes back (new questions get resolved
into Decisions, not left open), and reference the spec number in the commit. The full process,
including how to delegate implementation to subagents, is in
[docs/development/spec-driven-development.md](docs/development/spec-driven-development.md).
