# KosherEats / Mamiye-Eats — project instructions

> Kosher food delivery platform. Backend Go (`backend/`), web Next.js
> (`web/`), iOS Swift (`ios/`), Android Kotlin (`android/`). Fly app
> `koshereats-api`. Custom commands: `/sweep`, `/portparity`
> (`.claude/commands/`). Long autonomous fix/parity runs go through
> Temporal Orchestrator (`~/projects/temporal-orchestrator/`).

**UI/UX work of any kind**: read `docs/DESIGN_RUBRIC.md` first — the frozen
"Ember" design contract (dark canvas, brand-orange accent, semantic state
tokens, the kosher meat/dairy/pareve color triad), the screenshot scoring
rubric, and the simplicity metrics every polish loop ratchets against.
Token ground truth: `web/tailwind.config.ts` + `web/src/app/globals.css`.

## House rules
- **No work on Shabbat / Jewish holidays** — schedule autonomous loops around them.
- iOS→Android parity issues route through `/portparity`; batch small
  file-disjoint jobs through `/sweep`.
