export const meta = {
  name: 'work-queue',
  description: 'Barrier-free work queue: many fine-grained items each flow work->verify independently, so a freed agent immediately pulls the next queued item instead of idling at a barrier.',
  whenToUse: 'Batch work where items are independent and you want freed capacity to pull remaining work: bug-fix sweeps, iOS->Android parity porting (one screen per item), per-file refactors. NOT for work where stage 2 needs ALL of stage 1 first (dedup/merge) — that genuinely needs a barrier.',
  phases: [
    { title: 'Work', detail: 'one agent per item — no barrier between items' },
    { title: 'Verify', detail: 'each item is verified the instant its own work finishes' },
  ],
}

// ───────────────────────────────────────────────────────────────────────────
// THE TEACHING POINT
//
// The stalling pattern people hit:
//     const a = await parallel(items.map(it => () => agent(work(it))))   // BARRIER
//     const b = await parallel(a.map(r => () => agent(verify(r))))       // 2nd BARRIER
//   9 items finish, but NOTHING advances until the slow 1 finishes, because
//   parallel() is a barrier. The 9 freed agents sit idle with nothing to grab.
//
// This template uses pipeline() instead. pipeline has NO barrier between stages:
//   item A can be in Verify while item B is still in Work. The moment a
//   concurrency slot frees, the pool pulls the next queued item. That IS the
//   "finished agents help finish the rest" behavior — at the granularity of
//   ITEMS. The lever you control is how finely you split the work: 20 small
//   items keep freed agents busy; 2 giant items can't be subdivided mid-flight.
// ───────────────────────────────────────────────────────────────────────────

// --- args normalization -----------------------------------------------------
// Invoke as:  Workflow({ name: 'work-queue', args: [...] })
//   args = ["apps/seller/.../PayoutsScreen", "apps/seller/.../ReviewsScreen"]
//          → bare item list, default instruction, verify on
//   args = { items: [...], instruction: "Port this iOS screen to Android...",
//            verify: true }
//          → list + the instruction applied to every item
//   items may be strings, or objects { id, prompt } for richer per-item prompts.
let items = []
let instruction = 'Complete this work item end to end.'
let verify = true

if (Array.isArray(args)) {
  items = args
} else if (args && typeof args === 'object') {
  items = Array.isArray(args.items) ? args.items : []
  if (typeof args.instruction === 'string') instruction = args.instruction
  if (typeof args.verify === 'boolean') verify = args.verify
}

if (!items.length) {
  log('work-queue: no items. Pass args as ["item1","item2"] or {items:[...], instruction:"...", verify:true}.')
  return { error: 'no items provided', total: 0, results: [] }
}

const itemText = (it) => typeof it === 'string' ? it : (it && (it.prompt || it.id)) || JSON.stringify(it)
const itemId = (it, i) => typeof it === 'string' ? it : (it && (it.id || it.prompt)) || `item-${i}`
const short = (s) => { const t = String(s); return t.length > 40 ? t.slice(0, 37) + '...' : t }

const WORK_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['item', 'status', 'summary', 'filesTouched'],
  properties: {
    item: { type: 'string', description: 'the work item this result is for' },
    status: { type: 'string', enum: ['done', 'partial', 'blocked'] },
    summary: { type: 'string', description: 'one line: what you changed' },
    filesTouched: { type: 'array', items: { type: 'string' } },
    followups: { type: 'array', items: { type: 'string' }, description: 'leftover work, if any' },
  },
}

const VERIFY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['item', 'verdict', 'reason'],
  properties: {
    item: { type: 'string' },
    verdict: { type: 'string', enum: ['pass', 'fail'] },
    reason: { type: 'string' },
  },
}

log(`work-queue: ${items.length} items, verify=${verify}. pipeline() — no barrier, freed slots pull the next item.`)

// --- the queue: pipeline(), not parallel() ----------------------------------
// Stage callbacks receive (prevResult, originalItem, index). For stage 1,
// prevResult === the original item. A stage that throws drops that item to null
// and skips its remaining stages, so one bad item never stalls the rest.
const results = await pipeline(
  items,

  // Stage 1 — do the work for this one item.
  (it, _orig, i) => agent(
    `${instruction}\n\n` +
    `Work item:\n${itemText(it)}\n\n` +
    `Do the work now in the repo. Then return a structured result: status ` +
    `(done | partial | blocked), a one-line summary, the exact files you touched, ` +
    `and any follow-ups you did NOT finish.`,
    { label: `work:${short(itemId(it, i))}`, phase: 'Work', schema: WORK_SCHEMA }
  ),

  // Stage 2 — adversarially verify THIS item the moment its work completes.
  // No barrier: this runs for fast items while slow items are still in Stage 1.
  (work, it, i) => {
    if (!verify || !work) return work
    return agent(
      `Adversarially verify this completed work item. Actively try to find a ` +
      `reason it is NOT actually done or is incorrect — re-read the changed files.\n\n` +
      `Item: ${itemText(it)}\n` +
      `Claimed result: ${JSON.stringify(work)}\n\n` +
      `Return verdict pass | fail with a concrete reason. Default to fail if you ` +
      `cannot confirm it is correct.`,
      { label: `verify:${short(itemId(it, i))}`, phase: 'Verify', schema: VERIFY_SCHEMA }
    ).then(v => ({ ...work, verify: v }))
  }
)

// --- summary ----------------------------------------------------------------
const clean = results.filter(Boolean)
const passed = (r) => r.status === 'done' && (!verify || r.verify?.verdict === 'pass')
const done = clean.filter(passed)
const needsAttention = clean.filter(r => !passed(r))
const dropped = items.length - clean.length

log(`work-queue done: ${done.length}/${items.length} done & verified, ` +
    `${needsAttention.length} need attention, ${dropped} dropped (errored).`)

return {
  total: items.length,
  doneAndVerified: done.length,
  needsAttention,        // partial/blocked/failed-verify — your follow-up list
  dropped,
  results: clean,
}
