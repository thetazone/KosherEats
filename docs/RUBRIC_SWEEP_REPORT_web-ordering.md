# Rubric sweep report — `feat/web-ordering-rubric-sweep`

**Date:** 2026-09-13 (unattended)
**Result:** ✅ both jobs landed; all gates green; nothing pushed, nothing deployed.

| | |
|---|---|
| **Branch** | `feat/web-ordering-rubric-sweep` |
| **Created from** | `feat/web-ordering-selling-rebased` @ `c705eda8` |
| **HEAD** | `b48c821d` — `style(web/consumer): retokenize raw Tailwind hues to Ember tokens (83 -> 0)` |
| **Commits added** | 4 (1 cherry-pick + 3 sweep slices) + this report |
| **M3 raw hues** | **279 → 1** (rubric's own count; broad scope **288 → 3**) |
| **Branches untouched** | `main`, `feat/web-ordering-selling-rebased`, `feat/web-ordering-selling`, `feat/web-seller-geocode` |
| **Stash** | never used (shared stack left alone — the two pre-existing entries are unchanged) |

---

## 0. Reconciling the two starting numbers: 279 (rubric M3) vs 288 (rebase report)

Both are right; they are different scopes, and the difference is now pinned down
exactly. Measured at the pre-sweep ref `fc1bfbeb`:

| Scope | Count |
|---|---|
| **Rubric M3** — `app` + `components`, `*.tsx` only, utilities `text\|bg\|border\|ring\|placeholder\|divide\|from\|to` | **279** |
| …same regex, widened to all of `web/src` and `*.ts` + `*.tsx` | 283 (+4: `lib/orderStatus.ts`) |
| …widened again to the full utility list (`fill`, `stroke`, `via`, …) | **288** (+5 `fill-*` classes) |

So `REBASE_REPORT_web-ordering-selling.md` §2-F's **288** is reproducible — it
just counted `lib/` and `fill-*` too.

Per the brief, this report leads with the rubric's **own** M3 command (279 → 1)
and reports the broad figure alongside it (288 → 3). The sweep actually cleaned
both scopes: the `fill-*` classes and `lib/orderStatus.ts` were retokenized even
though M3 does not count them.

The job-1 cherry-pick was raw-hue-neutral (279 before it, 279 after), so 279 is
the honest "before" for the sweep either way.

---

## 1. The mapping table, extracted from `37569c45`

`git show 37569c45` (main, 2026-08-06, *"feat(web): design rubric + semantic
tokens + first polish round"*) is the only authority used. The table below was
derived mechanically: every `-`/`+` line pair in that diff was aligned and every
`<util>-<hue>-<shade>` token on the removed side matched to the same
`<util>-?-<shade>` on the added side. Counts are occurrences **in that commit**.

### 1a. Hue → token (the general rule)

| Raw hue | Ember token | Occurrences in `37569c45` | Pixel-identical? |
|---|---|---|---|
| `neutral-*` | `dark-*` | 144 | ✅ yes (the `dark` ramp is Tailwind neutral's hexes) |
| `gray-*` | `dark-*` | 2 | ✅ yes |
| `orange-*` | `brand-*` | 30 | ✅ yes (the `brand` ramp is Tailwind orange's hexes) |
| `red-*` | `danger-*` | 42 | ✅ yes (`danger: colors.red`) |
| `green-*` | `success-*` | 20 | ✅ yes (`success: colors.green`) |
| `yellow-*` | `warning-*` | 22 | ✅ yes (`warning: colors.yellow`) |
| `blue-*` | `info-*` | 9 | ✅ yes (`info: colors.blue`) |
| `purple-*` | `transit-*` | 2 | ✅ yes (`transit: colors.purple`) |
| **`amber-*`** | **`warning-*`** | **2** | ⚠️ **no** — see §1c |

### 1b. The kosher-triad override (context, not hue)

`37569c45` overrode the general rule in exactly one context: a **dietary badge**
(meat / dairy / pareve). Same shades, different alias.

| Raw hue | Token | Trigger seen in `37569c45` |
|---|---|---|
| `red-*` | `meat-*` | `<DietaryBadge label="Meat" color="bg-red-900/40 text-red-400" />` |
| `blue-*` | `dairy-*` | `<DietaryBadge label="Dairy" …>` |
| `green-*` | `pareve-*` | `<DietaryBadge label="Pareve" …>` |

Nothing else in that diff takes the triad, and `docs/DESIGN_RUBRIC.md` is explicit
that the triad is for dietary badges **only**.

### 1c. `amber-*` → `warning-*` — the rebase report is wrong about this

`REBASE_REPORT_web-ordering-selling.md` §2-E says the Ember palette *"has no alias"*
for `amber`. **It does, by precedent.** `37569c45` retokenized the admin
restaurants approval pill:

```
-                  : "bg-amber-500/20 text-amber-300";
+                  : "bg-warning-500/20 text-warning-300";
```

So `amber → warning` is an established mapping and the brief's rule ("mechanical
application of an established mapping = apply it") makes it in scope. **It was
applied**, to all 30 `amber-*` occurrences including `lib/orderStatus.ts`'s
`pending` pill.

**But flag it:** `warning-*` is Tailwind **yellow**, and `amber-400 #fbbf24` ≠
`yellow-400 #facc15`. Every other mapping in this sweep is hex-identical; this one
is a small deliberate hue shift onto an existing token. Main made that trade for
its own amber; this sweep follows it. **This is the single change most worth a
human veto.** Reverting it is a one-line change to the sweep script's map plus a
re-run (30 occurrences, 10 files).

### 1d. Hues with NO precedent — deliberately untouched

`sky`, `slate`, `zinc`, `stone`, `emerald`, `indigo`, `rose`, `teal`, `cyan`,
`lime`, `violet`, `fuchsia`, `pink` appear nowhere in `37569c45`, so no token was
guessed for any of them. Only **`sky`** actually occurs in this tree — see §4.

### 1e. Utilities

`37569c45` mapped the `text|bg|border|ring|placeholder|divide|from|to` utilities
the rubric's M3 regex counts, and it also carried `fill-*` onto tokens elsewhere
in the tree (`fill-brand-400` on the `restaurant/[id]` star predates this work).
The sweep therefore also cleaned **5 `fill-*` hue classes** that M3 does not count
but the rubric's "zero raw Tailwind hue classes" prose does. Verified in the built
CSS that `fill-warning-400` emits `fill:#facc15`.

---

## 2. Job 1 — the geocode cherry-pick

**Commit:** `fc1bfbeb` — `feat(web/seller): send geocoded lat/lng on restaurant create/update`
**Cherry-picked from:** `e748314e` (`feat/web-seller-geocode`)
**Conflicts: none.** `git cherry-pick -n e748314e` applied clean against the
rebased seller pages — the rebase had not touched the hunks it edits.

Files: `web/src/app/seller/onboarding/page.tsx`, `web/src/app/seller/settings/page.tsx`,
`web/src/lib/sellerApi.ts`, `web/src/types/seller.ts`.

Gate on its dependency, per `MORNING_HANDOFF.md` (*"merge only after
`feat/seller-latlng-api` is deployed"*): **cleared** — `78808ba9` is already an
ancestor of `main`, as the rebase report §5 noted.

Dependencies it needs were verified present on the rebased branch before gating:
`web/src/components/ui/AddressGeocodeField.tsx` exists, the `/api/geocode` route
exists, and `SellerRestaurant` already carries non-optional `lat`/`lng`.

What it does, in one line each:

- **onboarding** — replaces the two manual Latitude/Longitude `Field`s with
  `AddressGeocodeField` in composed mode (query built from street/city/state/zip);
  manual entry survives in the component's collapsible fallback.
- **settings** — adds `latField`/`lngField` state hydrated from the loaded
  restaurant (a stored `(0,0)` null island reads back as **blank**, so the
  "send neither" path stays natural), validates them as a BOTH-or-NEITHER pair
  with range + null-island guards, and puts them on the PUT body only when both
  are present.
- **sellerApi / types** — documents the both-or-neither contract and adds
  `lat?`/`lng?` to `UpdateRestaurantRequest`.

Because job 1 landed first, job 2's sweep covered these four files too (3 hue
classes in `seller/onboarding`, 7 in `seller/settings`).

---

## 3. Job 2 — the sweep

### Before / after

| Scope | Before (`fc1bfbeb`) | After |
|---|---|---|
| **Rubric M3** (`app` + `components`, `*.tsx`) | **279** | **1** |
| All of `web/src`, `*.ts` + `*.tsx`, full utility list (the rebase report's scope) | **288** | **3** |

**285 class occurrences were retokenized**, across 29 files, in 3 commits.

Every one was audited to be a pure rename: the sweep diff (`fc1bfbeb..HEAD`) was
re-parsed line-by-line and each `-`/`+` pair confirmed to differ **only** by
applying the §1 map. The audit mechanically verified **282** renames and found
**zero** unexplained edits. The only lines it could not pair 1:1 are all
deliberate and were read by hand:

- the 3 new `TODO(rubric)` comment lines and the rewritten `orderStatus.ts`
  header comment;
- `MenuItemForm`'s `dairy` entry, which Prettier reflowed from one line to four
  because `bg-dairy-500/15 border-dairy-500 text-dairy-400` is longer than the
  `blue-*` it replaced. Its **3** classes are the exact gap between the audit's
  282 and the 285 the before/after counts imply.

By hue (audited renames): `red` 177, `green` 55, `amber` 30, `yellow` 11,
`blue` 9 — plus the 3 `blue` → `dairy` classes on the reflowed line above.

### Per-slice commits

| # | Commit | Slice | M3 in slice |
|---|---|---|---|
| 1 | `309ef6bb` | `web/src/app/seller/**` + `web/src/components/seller/**` + `lib/orderStatus.ts` | **170 → 1** |
| 2 | `209113f2` | `web/src/components/**` (auth, orders, restaurant, ui) | **26 → 0** |
| 3 | `b48c821d` | `web/src/app/**` non-seller (account, orders, restaurant, deals, auth/forgot) | **83 → 0** |

Every slice was gated with `npx tsc --noEmit && npx next lint` before commit.
`tailwind.config.ts` was never touched and **no token was added**.

### Lines main had already retokenized

None were touched. At the branch point there were **zero** remaining `neutral-*`
or `orange-*` occurrences in `web/src` — last night's rebase had already reapplied
main's renames everywhere main made them. Every class this sweep changed was a
`red`/`green`/`blue`/`yellow`/`amber` class in a file the branch **adds**, or (in
the two `DietaryBadge` blocks) a line main *had* retokenized that the branch's
restructure then reintroduced raw. Restoring those is the opposite of overwriting
main's work.

---

## 4. What remains — and the exact hue that needs a human

### Per-file residue

| File | Line | Class | Hue | Why it was left |
|---|---|---|---|---|
| `web/src/components/seller/ActiveOrderCard.tsx` | 154 | `text-sky-300` | **`sky`** | No precedent in `37569c45` — the Ember palette has no `sky` alias and picking one is a design decision, not a sweep decision. `TODO(rubric)` added in place. |
| `web/src/lib/orderStatus.ts` | 35 | `bg-sky-500/15 text-sky-300` | **`sky`** | Same. Outside the rubric's M3 scope (it is a `.ts` file in `lib/`), but it is the source of truth for the `scheduled` pill, so it is listed here. `TODO(rubric)` updated in place. |

Only the first is counted by M3, hence **M3 = 1**; the broad scope counts all 3
classes, hence **288 → 3**. There is no other residue anywhere in `web/src`.

### Grouped by hue lacking a mapping

| Hue | Occurrences left | Where | What the decision is |
|---|---|---|---|
| **`sky`** | 2 (1 counted by M3) | both are the **`scheduled`** order state — the `orderStatus.ts` pill and the matching "Scheduled for …" line on `ActiveOrderCard` | `scheduled` is the only order state without a semantic alias. Either add a token (e.g. `scheduled: colors.sky`) to `tailwind.config.ts`, or reuse an existing one — `info-*` is the natural candidate, but `accepted` already owns `info`, so two states would render identically. **Both occurrences must move together.** |

**That is the whole list — `sky` is the only hue that still needs a human.**

### Three applied changes that deserve a design look

These are *not* residue — they are retokenized and the build is green. They are
listed because the mapping was mechanically correct but the resulting token's
**intent** is arguable. All are one-line reversals.

| Where | Now | Concern |
|---|---|---|
| `lib/orderStatus.ts` + 9 files (30 occurrences) | `amber-*` → `warning-*` | §1c — the only non-pixel-identical mapping in the sweep. Main established it; it is still a real (small) hue shift. |
| `components/restaurant/RestaurantCard.tsx:90` | favourite heart is `text-danger-500 fill-danger-500` | A favourite is not a danger state. Pixels are identical to the old `red-500`; the *name* now reads wrong. `brand-*` may be the right call. |
| `app/restaurant/[id]/page.tsx:568,576,584` | KashrusChip icons are `success-400` (Glatt) / `info-400` (Cholov Yisroel) / `warning-400` (Pas Yisroel) | These are kashrus attestations, not severities, and not the frozen meat/dairy/pareve triad. Green/blue pixels are unchanged; "Pas Yisroel = warning" is wrong intent, and amber→warning shifted its pixels besides. |

### Other simplicity metrics — no regression

Measured with the rubric's §3 commands at HEAD:

| Metric | Target | At HEAD | Note |
|---|---|---|---|
| M1 hex in TSX | 0 | **0** | the Google-SVG waiver no longer applies — those buttons were deleted on the branch (rebase report §2-A) |
| M2 arbitrary values | 0 + waivers | 187 | **untouched by this sweep** — inherited branch debt (`min-h-[44px]` touch targets dominate). A separate sweep. |
| M3 raw hues | 0 | **1** | this sweep: 279 → 1 |
| M4 inline `style={{` | ≤1 | 2 | **untouched** — `orders/page.tsx:507` (progress bar width) and `KosherCertificateModal.tsx:135` (zoom); both are genuinely dynamic |

---

## 5. Gate output (verbatim tails)

Run from `web/` at HEAD `b48c821d`. `web/` is **npm** (`package-lock.json`), not pnpm.

### Install
```
$ npm ci
(completed; only the usual audit/npm-version notices)
```

### Type-check
```
$ npx tsc --noEmit
TSC_EXIT=0
```
(no output)

### Lint
```
$ npx next lint
✔ No ESLint warnings or errors
LINT_EXIT=0
```

### Build
```
$ npx next build

  ▲ Next.js 14.2.35

   Creating an optimized production build ...
Browserslist: browsers data (caniuse-lite) is 6 months old. Please run:
  npx update-browserslist-db@latest
 ✓ Compiled successfully
   Linting and checking validity of types ...
   Collecting page data ...
 ✓ Generating static pages (31/31)
   Finalizing page optimization ...
   Collecting build traces ...

Route (app)                              Size     First Load JS
┌ ○ /                                    726 B           102 kB
├ ○ /account                             6.23 kB         111 kB
…
├ ƒ /seller/orders/[id]                  8.05 kB         108 kB
├ ○ /seller/settings                     6.71 kB         107 kB
└ ○ /terms                               1.95 kB         102 kB
+ First Load JS shared by all            87.3 kB
```
31 routes — identical to the rebase report's build. The only warning is the
unrelated `caniuse-lite` browserslist notice.

### Emitted-CSS spot check

Because a typo'd Tailwind class fails **silently** (no class emitted, no build
error), the built stylesheet was grepped for every new token family:

```
text-danger-400 -> 1      bg-meat-500    -> 1      text-dairy-400  -> 1
text-pareve-400 -> 1      fill-warning-400 -> 1    bg-pareve-900   -> 1
border-dairy-500 -> 1     text-warning-300 -> 1    text-sky-300    -> 1

.text-danger-400{color:rgb(248 113 113/…)}   ← == red-400 #f87171 ✅
.text-meat-400  {color:rgb(248 113 113/…)}   ← == red-400 #f87171 ✅
.text-warning-400{color:rgb(250 204 21/…)}   ← == yellow-400 #facc15 (was amber-400 #fbbf24) ⚠️
.fill-warning-400{fill:#facc15}
```

### Backend
Not touched — `git diff --stat main...HEAD -- backend/` is empty. Backend gates
skipped per the brief.

---

## 6. What to do next

1. **Decide `sky`** (§4). Two occurrences, one decision, they move together.
2. **Sanity-check the three flagged calls** in §4 — especially `amber → warning`,
   which is the only pixel-changing mapping in the sweep.
3. The rebase report's §2-A–§2-G judgment calls are all still open and unchanged
   by this run.
4. The manual smoke test (rebase report §4) still has not been run in a browser.
5. Nothing was pushed and nothing deployed; `main` and all three sibling web
   branches are byte-identical to how this run found them.
