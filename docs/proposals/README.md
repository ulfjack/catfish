# Proposals

Pre-ready thinking lives here. A proposal is a spec that still has **open questions** — it is not
yet ready to implement and is deliberately *not* subject to the `lint-specs` gate that governs
`docs/features/`.

Use this directory when you want to write down and circulate a design that still has unresolved
`- [ ]` items under **Open Questions**. Start from
[`../features/TEMPLATE.md`](../features/TEMPLATE.md); a proposal can carry as many open questions as
it needs.

When every open question has been resolved (with a human) into a **Decision** with rationale, move
the file to `docs/features/NNNN-title.md`, set `status: ready`, add it to the features index, and it
becomes a committable spec. See
[../development/spec-driven-development.md](../development/spec-driven-development.md) for the full
flow.
