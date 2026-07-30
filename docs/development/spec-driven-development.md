# Spec-driven development

Catfish is a low-level HTTP library where small mistakes have outsized consequences: a
mis-implemented flow-control transition can deadlock a connection, a lenient parser can become a
request-smuggling vector, and a careless public-API change breaks every application that embeds the
library. To keep changes deliberate — and to make AI-assisted development productive rather than
chaotic — we develop against **specs**.

The core idea is simple: **agree on what to build and how we'll know it's done, before writing the
code.** A spec is cheap to write and cheap to fix. An implementation is neither.

## When a spec is required

| Change | Spec? |
|---|---|
| New feature or capability | **Yes** |
| Change to public API (`de.ofahrt.catfish`, `…model`, `…model.server`) | **Yes** |
| Protocol behaviour / wire-format change | **Yes** |
| Security-relevant parsing or flow-control change | **Yes** |
| Bug fix with a subtle root cause or several plausible fixes | **Yes** (short spec) |
| Typo, comment, obvious one-line bug, test-only change | No |
| Pure refactor with no behaviour change | No (but say so in the commit) |

When in doubt, write the spec. It is usually a 20-minute document that saves hours of rework.

## The flow

```
   ┌─────────┐     ┌────────┐     ┌───────────┐     ┌──────────────┐     ┌────────┐
   │ 1 Frame │ ──▶ │ 2 Spec │ ──▶ │ 3 Review  │ ──▶ │ 4 Implement  │ ──▶ │ 5 Land │
   │  the    │     │  it    │     │  the spec │     │  against the │     │        │
   │ problem │     │        │     │           │     │  criteria    │     │        │
   └─────────┘     └────────┘     └───────────┘     └──────────────┘     └────────┘
                        ▲                                   │
                        └───────────── reality pushes back ─┘
```

### 1. Frame the problem

Before proposing a solution, state the problem in terms of who is affected and what breaks or is
missing. "git fetch of a large repo fails because the client sends a gzipped, chunked request body
and the server rejects `Content-Encoding`" is a frame. "Add gzip support" is not.

### 2. Write the spec

Copy [`docs/features/TEMPLATE.md`](../features/TEMPLATE.md) to
`docs/features/NNNN-short-title.md`, where `NNNN` is the next free number (zero-padded, e.g.
`0001`). The spec opens with YAML frontmatter (`id`, `title`, `status`, `owner`,
`architecture_refs`) and then a fixed set of sections. Fill in every one. The sections that matter
most:

- **Design** — enough design that a reviewer can spot a wrong turn. Name the classes/stages you'll
  touch. Call out the tricky part (there usually is one: a flow-control transition, a buffer
  lifetime, a header interaction).
- **Security Considerations** — for a low-level HTTP library this is rarely empty. Request
  smuggling, unbounded buffering, malformed input, TLS/ALPN, decompression bombs, NIO-thread
  blocking. Say "None" only after genuinely concluding there are none.
- **Decisions** — settled choices, each with a one-line rationale. Open questions that got resolved
  during review land here.
- **Acceptance Criteria** — a checkable list (`- [ ]`). Each item is something a test can assert or
  a reviewer can verify. These *are* the definition of done; the implementation is judged against
  them, not against a vibe.
- **Implementation Plan** — PR-sized boxes, ordered, each a non-breaking increment.

**Open Questions must be empty before a spec is committed.** A committed `docs/features/` spec is
`ready` — the pre-commit `lint-specs` gate rejects any unresolved `- [ ]` under *Open Questions*.
Resolve each open question (with a human) into a **Decision** with rationale first. Pre-ready
thinking that still has open questions lives in `docs/proposals/` (free-form, not linted) until it's
resolved and promoted into `docs/features/`.

Keep it short. A good Catfish spec is one to three pages. If it's longer, the change is probably
too big and should be split.

### 3. Review the spec

Get the spec reviewed **before** writing implementation code. For an AI agent this means: present
the spec to the human (or the reviewing agent) and get explicit sign-off. The whole point is that
disagreements surface here, where the cost of changing course is a paragraph edit.

A spec review asks:

- Is the problem real and worth solving now?
- Does the approach fit Catfish's architecture (non-blocking stages, explicit control,
  null-safety)?
- Is anything in the public API changing, and is that change justified and documented?
- Are the acceptance criteria complete and actually checkable?
- What's the security / correctness risk, and does the spec address it?

### 4. Implement against the criteria

Now write the code, driven by the acceptance criteria. Add tests that map to each criterion —
ideally you can point at the test that proves criterion _N_. Keep the build green
(`bazel test //...`) and formatted (`bazel run //:format`) as you go.

If the implementation reveals that the spec was wrong or incomplete — a case you didn't foresee, an
API that doesn't compose — **stop and update the spec**, then continue. A new question that surfaces
during implementation gets resolved with a human and moved into **Decisions** before you re-commit
(the Open Questions section must stay empty). The spec is a living document until the change lands;
a spec that lies about the code is worse than no spec.

#### Delegating implementation

Once a spec is reviewed and committed, its acceptance criteria are a self-contained work order.
That makes it a natural unit to **delegate to a subagent**: fork a child session, inline the spec
(or point at the committed `docs/features/NNNN-*.md`), and let it implement against the criteria
while you keep the planning/review context. Review the returned branch against the same criteria
before folding it in. Mechanical, well-specified work is exactly what this is good for; anything
requiring judgement about the spec itself should come back to review.

### 5. Land it

- Reference the spec in the commit message (e.g. "Implements docs/features/0002-chunked-gzip-uploads.md").
- Update the README if user-facing behaviour or public API changed.
- Tick the acceptance criteria in the spec (or note deviations), and set the spec's `status` to
  `implemented`.
- Ensure `bazel test //...` and `bazel run //:format.check` pass.

## Spec lifecycle

A spec's `status` frontmatter field moves through:

- **ready** — reviewed, open questions resolved into Decisions, signed off; implementation may
  proceed. This is the state a spec is committed to `docs/features/` in.
- **in-progress** — implementation underway.
- **implemented** — code merged, acceptance criteria met.
- **superseded** — replaced by a later spec. Leave the file in place for history and link to
  whatever replaced it.

Pre-ready thinking (still has open questions) is not a `docs/features/` spec at all — it lives in
`docs/proposals/` until resolved. Specs are immutable history once **implemented** — don't rewrite
an old spec to describe new work; write a new one that references it.

## Why this works for AI agents

- **The spec is the shared context.** An agent (or a fresh session, or a subagent) can pick up a
  spec and know exactly what "done" means without reverse-engineering intent from code.
- **Review happens where it's cheap.** Catching a wrong approach in a one-page spec is far cheaper
  than catching it in a reviewed, tested branch.
- **Acceptance criteria are testable.** They translate directly into tests, which keeps agents
  honest and makes the diff self-justifying.
- **It bounds scope.** A spec that's ballooning is a signal to split the work — before, not after,
  the code is written.
