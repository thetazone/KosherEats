# KosherEats Design Rubric — the "Ember" contract (web)

> **Purpose.** Single source of taste for every UI/UX loop agent (reviewer or
> fixer) on the **web app** (`web/`). Reviewers score pages from screenshots;
> fixers change code only in ways that raise the score. If a rule here
> conflicts with an agent's instinct, this file wins. The contract changes
> only by explicit human decision — never mid-loop.
>
> Token ground truth: `web/tailwind.config.ts` + `web/src/app/globals.css`.
> iOS (`ios/`) and Android (`android/`) carry their own token files; parity
> issues route through `/portparity`, but the ROLES defined here (brand,
> semantic states, kosher triad) are platform-invariant.

## 1. The frozen contract

### Palette — one accent, one neutral ramp, rationed states
- **Canvas is near-black**: `bg-dark-950` body, `dark-900` cards, `dark-800`
  surfaces/borders, `dark-700` strong borders. Text: white → `dark-300` →
  `dark-400` muted → `dark-500` faint.
- **Brand orange (`brand-500` #f97316) is the ONLY accent** — CTAs, links,
  active states, price highlights. `brand-600` hover, `brand-400` on-dark text
  accents. Two competing accents on one view = wrong.
- **Semantic states are named, never raw hues**: `success-*` (courier online,
  delivered), `warning-*` (pending), `danger-*` (errors, destructive),
  `info-*` (informational). These alias Tailwind green/yellow/red/blue —
  same pixels, greppable intent.
- **The kosher triad is a domain color code, frozen**: `meat-*` (red),
  `dairy-*` (blue), `pareve-*` (green). Dietary badges use ONLY these — a
  meat badge that isn't red is a correctness bug, not a style choice. Never
  use the triad for non-kosher meaning; never use `danger-*`/`info-*`/
  `success-*` on a dietary badge.
- **Zero raw Tailwind hue classes** (`neutral-*`, `orange-*`, `red-*`,
  `green-*`, `yellow-*`, `blue-*`, `gray-*`, …) and **zero hex literals in
  TSX**. Waiver: third-party brand marks (the Google sign-in SVG) keep their
  official colors, marked with a `RUBRIC-WAIVER` comment.

### Shape & type
- Radii idiom: `rounded-xl` controls/inputs, `rounded-2xl` cards, `rounded-full`
  pills/badges. No arbitrary radii.
- Inter everywhere (400–800). Weights carry hierarchy: 800 page titles,
  700 section heads, 600 buttons/labels, 400 body.
- Spacing on Tailwind's default scale — no `[Npx]` values. Waiver: arbitrary
  `aspect-[4/3]`-style ratios (Tailwind 3 has no scale for them) and the two
  decorative hero blob sizes on the landing page.

### Components — use this, not that
| Need | Use | Never |
|---|---|---|
| Primary action | `.btn-primary` | ad-hoc orange button stacks |
| Secondary action | `.btn-secondary` | hand-rolled bordered buttons |
| Surface | `.card` | one-off `bg-dark-900 rounded-… border-…` stacks |
| Text/search input | `.input` (+ `SearchBar`) | styled raw `<input>` |
| Restaurant tile | `RestaurantCard` | bespoke tiles |
| Page chrome | `Header` | per-page headers |

Promote any pattern used on 2+ pages into `globals.css` `@layer components`
or `web/src/components/` — that's a simplicity win.

## 2. Screenshot review rubric (the reviewer's scorecard)

Score each page 1–5 per dimension **from a fresh screenshot**. Target ≥ 4
everywhere; a page passes at ≥ 4 across the board for two consecutive rounds.
Consumer pages (`/`, `/search`, `/restaurant/[id]`, `/cart`, `/orders`,
`/auth`) AND admin pages (`/admin/**`) both count.

| # | Dimension | 5 looks like | 1 looks like |
|---|---|---|---|
| 1 | **Hierarchy** | One primary action; prices and CTAs pop; scannable cards | Competing oranges, flat gray walls |
| 2 | **Token compliance** | Named tokens only; component classes reused | Raw hues, ad-hoc surface stacks |
| 3 | **Kosher clarity** | Meat/Dairy/Pareve instantly readable, consistent everywhere | Triad colors misused or inconsistent |
| 4 | **Density & rhythm** | Food imagery breathes; admin tables tight but aligned | Cramped cards, ragged grids |
| 5 | **States** | Loading skeletons, designed empty states, clear error banners | Blank screens, spinner-only, silent failures |
| 6 | **Legibility/a11y** | AA contrast on dark-950; ≥44px targets; visible focus | dark-500 body text, focus-invisible buttons |
| 7 | **Calm** | Dark canvas recedes, food and CTAs advance | Glowing borders, noisy badges, shadow soup |

Reviewer output per page: scores + the **single highest-leverage fix**.
Severity = (5 − lowest score) × 2 on the orchestrator's 1–10 scale.

## 3. Simplicity metrics (run from `web/src/`)

```bash
# M1 — hex literals in TSX (target: 0 + waived Google SVG)
grep -rEn "#[0-9A-Fa-f]{3,8}\b" app components --include='*.tsx' | grep -v "RUBRIC-WAIVER" | wc -l
# M2 — arbitrary values (target: 0 + waived aspect ratios / hero blobs)
grep -rEo '(text|bg|border|rounded|p|m|gap|leading|tracking)[a-z-]*-\[[^]]*\]' app components --include='*.tsx' | wc -l
# M3 — raw Tailwind hue classes (target: 0)
grep -rEo '[a-z:]*(text|bg|border|ring|placeholder|divide|from|to)-(gray|slate|zinc|neutral|stone|orange|red|blue|green|amber|yellow|emerald|sky|indigo|purple)-[0-9]+' app components --include='*.tsx' | wc -l
# M4 — inline style= (target: ≤1, the existing dynamic case)
grep -rn 'style={{' app components --include='*.tsx' | wc -l
```

Soft ceilings: ≤400 LOC per page file, JSX nesting ≤6.

### Baseline — 2026-08-06
| Metric | Count | Note |
|---|---|---|
| M1 hex in TSX | 4 | all Google-logo SVG (waived) |
| M2 arbitrary | 6 | aspect ratios + hero blobs (waived) |
| M3 raw hues | 283 | 147 neutral + 30 orange (hex-identical renames), ~100 states |
| M4 inline style | 1 | |
| Pages/components | 20 files, 4512 LOC | |

## 4. Loop protocol

1. **Screenshot first** — reviewer scores from pixels (Chrome MCP against
   `npm run dev` in `web/`; API defaults to prod, so real data renders).
2. **Reviewer ≠ fixer**; fixer gets scores + one highest-leverage fix per page.
3. **Re-verify on fresh pixels** — never accept the fixer's claim.
4. **Ratchet**: rubric scores monotonically non-decreasing AND ≥1 simplicity
   metric strictly decreasing per round; a trade is a failed round — revert.
5. **Exit**: all pages ≥ 4 on every dimension for 2 consecutive rounds and
   M1–M4 at target.
6. **Cross-platform**: web is the lead surface for this loop; when a fix
   changes a role (not just a class), file an iOS/Android parity ticket via
   `/portparity` rather than silently diverging.
