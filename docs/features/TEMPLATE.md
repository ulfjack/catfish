# NNNN — <Short title>

- **Status:** Draft
- **Author(s):** <name / agent>
- **Created:** <YYYY-MM-DD>
- **Related:** <links to other specs, issues, or README sections; "none" if none>

## Problem

What is broken or missing, and who is affected? Frame it concretely — a scenario that fails today,
or a capability an embedding application cannot express. Avoid jumping to a solution here.

## Goals

- What this change will accomplish (bullet list).

## Non-goals

- What this change explicitly will **not** do, to bound scope.

## Approach

The design, in enough detail that a reviewer can spot a wrong turn without reading the eventual
diff. Name the classes, stages, and packages you'll touch. Describe new types or methods. Call out
the tricky part — the flow-control transition, the buffer lifetime, the header interaction, the
parser edge case — because there almost always is one.

If there are alternatives worth mentioning, say why you chose this one.

## Public API impact

Does this change anything in `de.ofahrt.catfish`, `de.ofahrt.catfish.model`, or
`de.ofahrt.catfish.model.server`? List new/changed/removed public types and methods. If nothing
changes, say "None." Note any README updates required.

## Security & correctness considerations

For a low-level HTTP library this section is rarely empty. Consider: request smuggling and framing
ambiguity, resource-exhaustion / unbounded buffering, malformed-input handling, TLS/ALPN behaviour,
and whether the NIO thread can ever block. State how the design addresses each relevant risk.

## Acceptance criteria

A numbered, checkable list. Each item should be something a test can assert or a reviewer can
directly verify. This is the definition of done.

1. …
2. …
3. …

## Testing plan

Which tests prove the acceptance criteria (unit, integration, parser conformance)? Note anything
that needs a new test fixture or a real external client (e.g. `git`, `curl --http2`).

## Rollout / compatibility notes

Backwards-compatibility implications, default-behaviour changes, and anything an embedding
application must know when upgrading. "None" if not applicable.
