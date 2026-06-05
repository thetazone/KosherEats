---
description: Deli-counter sweep — fan a batch of small independent jobs through the work-queue (no barrier)
---

Run a barrier-free "deli counter" sweep over a batch of small, independent jobs
using the **work-queue** workflow. The point: keep every agent busy by feeding
many small tickets, so a freed agent immediately pulls the next one instead of
idling at a barrier.

`$ARGUMENTS` describes the batch. It can be:
- an explicit list of items (one per line, or comma-separated), or
- a description of work to discover first (e.g. "fix the 12 lint/type errors in
  the courier Android app", "port the seller checkout flow").

This is a polyglot repo — `ios/` (Swift), `android/` (Kotlin), `backend/` (Go),
`web/` (Next.js), `shared/`. Keep each item within ONE of those so the right
build/test applies and agents don't collide.

Steps:
1. Turn the batch into a list of **small, FILE-DISJOINT items** (the deli
   tickets) — one file/screen/unit per item. If the batch is a description,
   discover the concrete items first and show me the list before launching.
2. Launch the queue:
   `Workflow({ name: 'work-queue', args: { items: [...], instruction: <what to
   do to each item, derived from the batch>, verify: true } })`
3. When it returns, surface results **inline, needs-attention first** (failed
   verify → blocked → partial → done), sorted by severity. Don't just link a report.

Keep items small and many — that's what keeps everyone busy. If two items would
touch the same file, merge them. Multiple agents spawn (token cost) — explicit
opt-in run. For long, paced, continuous parity work prefer the Temporal
Orchestrator; use this for a focused on-demand batch.
