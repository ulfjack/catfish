---
id: NNNN
title: <short descriptive title>
status: ready        # ready | in-progress | implemented | superseded
owner: <name>
architecture_refs:   # sections/files in ARCHITECTURE.md this touches
  - <e.g. Proxy>
---

# NNNN — <Title>

## Summary

One or two sentences: what this change is and why it matters.

## Goals

- What this spec is trying to achieve (bullet list, each independently checkable).

## Non-Goals

- Explicitly out of scope. Prevents scope creep during implementation.

## Background / Context

Any context the implementing agent needs: current behavior, relevant code,
constraints, prior decisions. Link to ARCHITECTURE.md sections.

## Design

The proposed design. Include data/schema changes, API/interface changes,
error handling, and how it fits the existing architecture.

## Security Considerations

Security and correctness risks this change touches, and how the design addresses each. For a
low-level HTTP library this section is rarely empty — consider request smuggling / framing
ambiguity, resource exhaustion / unbounded buffering, malformed-input handling, TLS/ALPN behaviour,
decompression bombs, and whether the NIO thread can ever block. Write "None" only after genuinely
concluding there are none.

## Decisions

Settled choices, each with a short rationale. (These are NOT open questions.)

- **Decision:** ... — *Rationale:* ...

## Open Questions

Unresolved questions. This section **must have no unresolved items (`- [ ]`)
before a spec can be committed** — the precommit spec-lint enforces it. Pre-ready
thinking with open questions lives in `docs/proposals/` until it's resolved;
once a spec lands in `docs/features/`, its open questions are resolved into
Decisions. During implementation, new questions get resolved with a human and
moved to Decisions before re-committing.

- [ ] <question> — *proposed answer:* ...

## Acceptance Criteria

Testable checklist that defines "done" for the whole spec.

- [ ] ...
- [ ] Tests: ...

## Implementation Plan

Break the work into small, PR-sized units. Each box = one PR. Order matters;
prefer non-breaking, incremental steps.

- [ ] PR 1: ...
- [ ] PR 2: ...

## Notes

Anything else: risks, follow-ups, links to related specs.
