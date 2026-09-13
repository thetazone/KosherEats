# Rubric sweep report — M2 (arbitrary values) — `feat/web-ordering-rubric-sweep-m2`

**Date:** 2026-09-13 (unattended)
**Result:** ✅ sweep landed; all gates green; nothing pushed, nothing deployed.

| | |
|---|---|
| **Branch** | `feat/web-ordering-rubric-sweep-m2` |
| **Created from** | `feat/web-ordering-rubric-sweep` @ `2f3693b6` |
| **Commits added** | 3 sweep slices + this report/marker commit |
| **M2, rubric's own count** | **187 → 37** (39 on the final tree — see the caveat in §2) |
| **M2, real classes only** | **184 → 36** (the grep also matches 3 comment lines) |
| **The 36 residue** | 15 are design calls (all marked), 5 are rubric-waived ratios, 16 have no scale equivalent |
| **Branches untouched** | `main`, `feat/web-ordering-rubric-sweep`, and all siblings |
| **Stash** | never used (shared stack left alone) |

This is the follow-up to `RUBRIC_SWEEP_REPORT_web-ordering.md`, whose §4 closed by
noting M2 stood at **187** and was "inherited branch debt … a separate sweep."
This is that sweep.

---

## 1. The mapping that was applied

The rubric (`docs/DESIGN_RUBRIC.md` §"Shape & type") says:

> Spacing on Tailwind's default scale — no `[Npx]` values. Waiver: arbitrary
> `aspect-[4/3]`-style ratios (Tailwind 3 has no scale for them) and the two
> decorative hero blob sizes on the landing page.

Only **exact** scale equivalents were mapped. Nothing was rounded onto a nearby
scale key; anything off-scale got a `TODO(rubric)` marker instead (§3a).

| Arbitrary value | Tailwind scale class | Occurrences | Computed value | Exact? |
|---|---|---|---|---|
| `min-h-[44px]` | `min-h-11` | **126** | `2.75rem` = 44px | ✅ |
| `min-w-[44px]` | `min-w-11` | **19** | `2.75rem` = 44px | ✅ |
| `min-h-[20px]` | `min-h-5` | 1 | `1.25rem` = 20px | ✅ |
| `min-h-[1.25rem]` | `min-h-5` | 1 | `1.25rem` | ✅ (unit-identical) |
| `max-w-[16rem]` | `max-w-64` | 1 | `16rem` | ✅ (unit-identical) |
| `max-w-[14rem]` | `max-w-56` | 1 | `14rem` | ✅ (unit-identical) |
| `max-w-[10rem]` | `max-w-40` | 1 | `10rem` | ✅ (unit-identical) |
| | | **150** | | |

**148 of those 150 are real classes.** The other **2** are prose: an explanatory
comment duplicated in two files —

```
- // min-h-[44px] keeps every status action a full-size touch target at 375px.
+ // min-h-11 keeps every status action a full-size touch target at 375px.
```

— at `web/src/app/seller/orders/[id]/page.tsx:503` and
`web/src/components/seller/ActiveOrderCard.tsx:199`. Updating them was correct:
the comment names the class on the line below it, and leaving it saying
`min-h-[44px]` would have made it wrong. It is called out because it is the gap
between "150 replacements" and "148 classes."

`tailwind.config.ts` contains no `spacing` or `fontSize` override (only an
`extend` for the Ember colors), and no stylesheet sets a root `font-size`, so
Tailwind's stock scale is in force. **No token or scale key was added.**

### Emitted-CSS verification

Because a typo'd Tailwind class fails **silently**, the built stylesheet was
grepped for every class this sweep introduced:

```
.min-h-11{min-height:2.75rem}    .max-w-64{max-width:16rem}
.min-w-11{min-width:2.75rem}     .max-w-56{max-width:14rem}
.min-h-5 {min-height:1.25rem}    .max-w-40{max-width:10rem}
```

All six emit; all six match the value they replaced.

### ⚠️ The one judgment call worth a human look: px → rem on touch targets

145 of the 150 replacements are the **44px touch target**
(`min-h-[44px]` / `min-w-[44px]` → `min-h-11` / `min-w-11`). This is
pixel-identical **at the default 16px root font-size**, which is what the app
ships. But it is a change in kind:

- `min-h-[44px]` is an **absolute** guarantee — always ≥44 CSS px.
- `min-h-11` is `2.75rem`, i.e. **relative to the user's browser font-size**.

A user who sets a *smaller* default font size in their browser drops these
controls below the 44px floor that Apple's HIG and WCAG 2.5.5 ask for. A user who
sets a larger one gets larger targets, which is fine.

**The comment quoted above is direct evidence this matters:** whoever wrote
`min-h-[44px]` documented it as *"a full-size touch target at 375px"* — an
accessibility floor, not a spacing choice.

The rubric explicitly asks for the scale (`no [Npx] values`) and the scale is
rem-based, so the sweep followed the rubric. If that floor must stay absolute,
the fix is a rubric amendment plus a waiver comment on those classes — not a
re-run, since `min-h-11` is what the rubric as written demands.

### Audit: every changed line is a pure mapping

The sweep diff (`git diff feat/web-ordering-rubric-sweep..HEAD -- web/src`) was
re-parsed hunk by hunk. All **124** hunks pair `-`/`+` lines **1:1** (no reflow,
no Prettier churn), and applying the §1 table to each removed line reproduces the
added line **exactly**:

```
hunks: 124
lines verified as pure mapping: 131
lines NOT explained by mapping: 0
hunks with unequal -/+ counts: 0
```

(131 lines carry 150 replacements — several lines hold both `min-h-` and `min-w-`.)
**Zero unexplained edits.** No arbitrary value was *added* anywhere in the sweep.
The two `min-w-0` classes visible on the `+` side are pre-existing and appear
unchanged on the `-` side of the same lines.

---

## 2. Before / after

### The rubric's own M2 command, run from `web/src`

```
grep -rEo '(text|bg|border|rounded|p|m|gap|leading|tracking)[a-z-]*-\[[^]]*\]' \
  app components --include='*.tsx' | wc -l
```

| | Rubric grep | of which comment lines | **real classes** |
|---|---|---|---|
| Base `2f3693b6` | **187** | 3 | **184** |
| HEAD `e4497a86` (committed sweep) | **37** | 1 | **36** |
| **Final tree (this commit)** | **39** | 3 | **36** |

184 − 148 = **36**. ✅ The commit subjects and the headline figure use the
rubric's own number (187 → 37) because that is the metric the rubric defines; the
real-class column is the honest one.

**Why the grep over-counts.** It matches any occurrence of the pattern, including
inside comments. Three comment lines on the final tree quote a bracketed value:

| File | Line | Why it matches |
|---|---|---|
| `web/src/components/checkout/CheckoutPanel.tsx` | 1251 | **Pre-existing** prose: *"padded past the home indicator on notched phones (`pb-[env(safe-area-inset-bottom)]`)."* Present at the base ref too. The real class is at 1258. |
| `web/src/app/account/page.tsx` | 415 | `TODO(rubric)` marker quoting `tracking-[0.5em]`, the value it is about |
| `web/src/components/auth/VerificationGate.tsx` | 435 | same marker, same value |

The count went 37 → 39 because the two `tracking-[0.5em]` markers were added by
this sweep and quote their subject. **This is noted rather than "fixed"** —
rewording a marker to dodge a regex would make it a worse comment. The other
markers say `11px` / `10px` rather than `text-[11px]`, so they do not self-match.
At the base ref the same distortion existed (3 comment lines), so 187 was never
184 real classes either; the sweep did not introduce the artifact, only shifted it
by two.

### Per slice

| # | Commit | Slice | M2 in slice (rubric grep) |
|---|---|---|---|
| 1 | `6350ea64` | `web/src/app/seller/**` + `web/src/components/seller/**` | **89 → 16** |
| 2 | `58d06a43` | `web/src/components/**` (auth, checkout, layout, orders, restaurant, ui) | **35 → 13** |
| 3 | `e4497a86` | `web/src/app/**` non-seller (account, orders, restaurant, search) | **63 → 8** |
| | | **total** | **187 → 37** |

89+35+63 = 187 and 16+13+8 = 37; each figure was re-measured at both refs with
`git grep` rather than trusted from the commit subjects. **26 files** changed.

### Other rubric metrics — no regression

Measured with the rubric's §3 commands on the final tree:

| Metric | Target | At HEAD | Note |
|---|---|---|---|
| M1 hex in TSX | 0 | **0** | unchanged |
| M2 arbitrary | 0 + waivers | **37** grep / **36** real | this sweep: 187 → 37 |
| M3 raw hues | 0 | **1** | unchanged — the `sky` decision from the M3 report is still open |
| M4 inline `style={{` | ≤1 | **2** | unchanged — both genuinely dynamic |

---

## 3. Residue — the 36, grouped by value

### 3a. Design decisions for Salto (15) — all carry a `TODO(rubric)` marker

These are real off-scale values. Tailwind **has** a neighbouring scale key, so
snapping them is possible — but it changes rendered size, which is a design call,
so the sweep did not round them.

**`text-[11px]` × 10** — Tailwind's nearest is `text-xs` = **12px** (+1px).

| File | Line | Marker |
|---|---|---|
| `web/src/app/restaurant/[id]/page.tsx` | 83 | ✅ |
| `web/src/app/seller/deals/page.tsx` | 257 | ✅ |
| `web/src/app/seller/menu/page.tsx` | 474 | ✅ |
| `web/src/app/seller/menu/page.tsx` | 480 | ✅ (shares the 474 marker, which names this "Paused" pill explicitly) |
| `web/src/app/seller/orders/[id]/page.tsx` | 825 | ✅ |
| `web/src/app/seller/orders/[id]/page.tsx` | 873 | ✅ **added in this commit** |
| `web/src/app/seller/orders/[id]/page.tsx` | 965 | ✅ **added in this commit** |
| `web/src/app/seller/page.tsx` | 495 | ✅ |
| `web/src/components/orders/OrderChat.tsx` | 252 | ✅ |
| `web/src/components/seller/ModifierGroupsEditor.tsx` | 146 | ✅ |

**`text-[10px]` × 3** — Tailwind's nearest is `text-xs` = **12px** (+2px).

| File | Line | Marker |
|---|---|---|
| `web/src/app/restaurant/[id]/page.tsx` | 858 | ✅ |
| `web/src/app/seller/orders/page.tsx` | 377 | ✅ |
| `web/src/components/checkout/CheckoutPanel.tsx` | 1016 | ✅ |

**`tracking-[0.5em]` × 2** — Tailwind's widest is `tracking-widest` = **0.1em**
(5× tighter). Deliberate OTP digit spacing; `tracking-widest` would visibly close
it up.

| File | Line | Marker |
|---|---|---|
| `web/src/app/account/page.tsx` | 427 | ✅ (marker at 415) |
| `web/src/components/auth/VerificationGate.tsx` | 447 | ✅ (marker at 435) |

**These two are the same OTP field in two places and must move together.**

So the whole human decision set is three questions:

1. **`text-[11px]` → `text-xs` (12px)?** 10 call sites, all badges/pills/captions.
2. **`text-[10px]` → `text-xs` (12px)?** 3 call sites. A 20% jump — the tightest
   is the "Subtotal" label inside the checkout button, where vertical room is
   scarce.
3. **`tracking-[0.5em]`** — keep the arbitrary value and add a rubric waiver, or
   add a `tracking` scale key (e.g. `otp: '0.5em'`)? The rubric's waiver list
   currently has no entry for it.

Options 1 and 2 are each a one-line `sed`; option 3 is a config or rubric edit.

### 3b. Rubric-waived aspect ratios (5) — left alone, no marker needed

The rubric waives these by name. They appear in the count because the M2 regex's
`p[a-z-]*-\[` alternative matches the tail of `aspect-[…]` (as `pect-[4/3]`).

| Value | File | Line |
|---|---|---|
| `aspect-[4/3]` | `web/src/app/admin/couriers/page.tsx` | 295 |
| `aspect-[4/3]` | `web/src/app/admin/couriers/page.tsx` | 308 |
| `aspect-[4/3]` | `web/src/app/seller/onboarding/page.tsx` | 434 |
| `aspect-[4/3]` | `web/src/app/seller/settings/page.tsx` | 553 |
| `aspect-[3/4]` | `web/src/app/page.tsx` | 371 |

**On the other waiver:** the rubric also waives "the two decorative hero blob
sizes on the landing page." Both still exist — `app/page.tsx:210-211`,
`w-[600px] h-[600px]` and `w-[400px] h-[400px]` — but bare `w-`/`h-` are **not**
in the M2 regex's utility list, so they never counted toward the 187 and do not
count toward the 36. The waiver is intact and unused.

### 3c. No Tailwind scale equivalent exists (16) — not a design decision

These are viewport units, safe-area insets, percentages and `calc()`. Tailwind 3
has **no** scale key for any of them, so there is nothing to map them onto — they
are in the same structural category as the waived aspect ratios, not in the
"someone must choose a size" category. They carry **no** `TODO(rubric)` marker,
deliberately: 16 markers pointing at non-decisions would bury the 15 that are
real. Listed here for completeness.

**Safe-area insets (9)** — required for iOS home-indicator clearance; `env()`
cannot be expressed on the scale.

| Value | File | Line |
|---|---|---|
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/app/restaurant/[id]/page.tsx` | 829 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/app/restaurant/[id]/page.tsx` | 845 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/app/seller/deals/page.tsx` | 626 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/components/checkout/CheckoutPanel.tsx` | 1258 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/components/restaurant/KosherCertificateModal.tsx` | 116 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/components/restaurant/KosherFilterPanel.tsx` | 344 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/components/restaurant/MenuItemModal.tsx` | 328 |
| `pb-[calc(1rem+env(safe-area-inset-bottom))]` | `web/src/components/seller/MenuItemForm.tsx` | 386 |
| `pb-[calc(0.75rem+env(safe-area-inset-bottom))]` | `web/src/components/orders/OrderChat.tsx` | 273 |

*Optional cleanup, not required by the rubric:* the 8 identical
`pb-[calc(1rem+env(…))]` strings are a copy-paste idiom across 8 files. A single
`.pb-safe` utility in `globals.css` (or a `spacing` key) would collapse all 9 and
remove them from the M2 count as a side effect. That is a refactor, so it was
left out of a mechanical sweep.

**Viewport-relative modal/message sizing (7)** — `vh`/`dvh`/`vw`/`%` have no
scale key.

| Value | File | Line |
|---|---|---|
| `max-h-[85vh]` | `web/src/components/checkout/CheckoutPanel.tsx` | 1136 |
| `max-h-[85vh]` | `web/src/components/restaurant/MenuItemModal.tsx` | 179 |
| `max-h-[90vh]` | `web/src/app/seller/deals/page.tsx` | 446 |
| `max-h-[90vh]` | `web/src/components/seller/MenuItemForm.tsx` | 178 |
| `max-h-[calc(100dvh-2rem)]` | `web/src/components/orders/CourierRatingModal.tsx` | 90 |
| `max-w-[75%]` | `web/src/components/orders/OrderChat.tsx` | 237 |
| `max-w-[calc(100vw-2rem)]` | `web/src/app/seller/orders/[id]/page.tsx` | 466 |

*Consistency note, not a rubric violation:* modal max-height is `85vh` in two
places, `90vh` in two more, and `calc(100dvh-2rem)` in a fifth. Only the `dvh`
one is correct on mobile Safari, where `vh` ignores the collapsing URL bar. Worth
standardising on the `dvh` form — a behavioural fix, out of scope here.

### 3d. Marker-coverage fix made in this commit

The mechanical sweep's marker pass was **incomplete**: 8 of the 10 `text-[11px]`
sites had markers, but `web/src/app/seller/orders/[id]/page.tsx` lines **873**
and **965** (the "Hand off to Uber Direct" hint and the "Delivery ID" line) had
none, despite the same file carrying a marker at 823. Two markers were added in
this commit so every one of the 15 decisions in §3a is annotated in place. The
added comments do not quote a bracketed value, so they do not affect the grep
count.

JSX comment syntax was chosen per position: `{/* … */}` in JSX children, `// …`
inside a `{cond && ( … )}` expression. Both forms were already used by the
sweep's own markers, and `tsc`/`next build` confirm neither renders as text.

---

## 4. Gate output (verbatim tails)

Run from `web/` on the final tree (sweep + markers + this report).
`web/` is **npm** (`package-lock.json`), not pnpm.

### Install
```
$ npm ci
skipped — node_modules/ already present and in sync with package-lock.json
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
  Why you should do it regularly: https://github.com/browserslist/update-db#readme
 ✓ Compiled successfully
   Linting and checking validity of types ...
   Collecting page data ...
   Generating static pages (0/31) ...
 ✓ Generating static pages (31/31)
   Finalizing page optimization ...
   Collecting build traces ...

Route (app)                              Size     First Load JS
┌ ○ /                                    725 B           102 kB
├ ○ /account                             6.22 kB         111 kB
…
├ ƒ /seller/orders/[id]                  8.03 kB         108 kB
├ ○ /seller/settings                     6.71 kB         107 kB
└ ○ /terms                               1.95 kB         102 kB
+ First Load JS shared by all            87.3 kB
BUILD_EXIT=0
```

31 routes — identical to the M3 sweep report's build. The only warning is the
unrelated `caniuse-lite` browserslist notice. Route sizes are unchanged from that
report to within a byte or two, as expected for a pure class rename.

### Backend
Not touched — this sweep is `web/src` only.

---

## 5. What to do next

1. **Answer the three questions in §3a** — `text-[11px]`, `text-[10px]`, and
   `tracking-[0.5em]`. That is the entire human decision set for M2; each is a
   one-line change once decided.
2. **Look at the px → rem touch-target note in §1.** 145 of the 150 replacements
   are the 44px tap target, now rem-relative, and the code comment says it was
   written as an a11y floor. The rubric as written demands the rem scale; confirm
   that is what you want.
3. **Consider a `.pb-safe` utility** (§3c) — collapses 9 of the 16 structural
   arbitrary values and is a genuine de-duplication.
4. **Standardise modal max-height on `dvh`** (§3c) — a real mobile-Safari bug,
   independent of the rubric.
5. **Optional:** the rubric's M2 command counts comments. Adding
   `| grep -v '^\s*\(//\|{\?/\*\|\*\)'` would make it measure classes only, and
   the target "0 + waivers" would then be literally checkable.
6. The `sky` decision from `RUBRIC_SWEEP_REPORT_web-ordering.md` §4 is still
   open, as are that report's other flagged calls.
7. Nothing was pushed and nothing deployed; `main` and every sibling branch are
   byte-identical to how this run found them.
