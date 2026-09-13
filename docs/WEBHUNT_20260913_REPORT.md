# webhunt-20260913 — web-first pre-merge campaign

Branch `feat/web-hunt-20260913` (stacked on `feat/web-ordering-rubric-sweep-m2`). Nothing pushed, nothing deployed. Generated 2026-09-13 13:56.

## Phases

| Phase | Area | Rounds | Fixed | Gate | Commit |
|---|---|---|---|---|---|
| hunt | `web` | 4 | 12 | GREEN | `eefef7b7` |
| uiux | `ke_uiux_web` | 3 | 9 | GREEN | `ede30f59` |
| backend | `ke_bugs_backend` | 3 | 9 | GREEN | `834035e4` |

## hunt — `web`

Findings across all rounds, severity descending:

| Sev | Round | Finding | Status |
|---|---|---|---|
| 8 | 3 | Stuck pending_order permanently locks checkout on the browser (and leaks across users) | FIXED |
| 7 | 1 | fetchAPI drops the HTTP status; 10 duplicated substring `isUnauthorized` helpers miss real 401 bodies, breaking sign-out routing and the charged-but-no-order recovery path | FIXED |
| 7 | 2 | A transient failure of POST /auth/refresh wipes the session and force-logs-out the user (consumer + seller) | FIXED |
| 7 | 4 | Stale PaymentIntent window lets Place Order charge a PI priced against the previous address/tip/deal | FIXED |
| 6 | 2 | Seller area never routes a dead session to /seller/login — expired sellers see raw 'refresh token revoked' errors and a 15 s poll loop | FIXED |
| 6 | 3 | Post-capture exception is reported as "Payment failed" with Pay re-enabled → double-charge path | FIXED |
| 6 | 4 | Sign-out deletes the captured-charge PendingOrder recovery record, reopening the double-pay path | FIXED |
| 5 | 1 | Silent refresh never reaches page state, so after 15 min every consumer request becomes 401 → POST /auth/refresh → replay, indefinitely | FIXED |
| 5 | 2 | 'Payment methods' page saves cards that can never be seen, used, or removed on web — misleading dead-end feature | FIXED |
| 5 | 3 | Closed restaurants are fully orderable on web — charge, then auto-reject/refund 10 min later | FIXED |
| 4 | 1 | Open/closed toggle and delivery-mode selector can be reverted by an in-flight 15 s poll (no request-sequence bump on mutation) | FIXED |
| 4 | 4 | Server cart can be mutated from other pages while a captured charge is still in recovery, making recovery unconvergeable | FIXED |

Round summaries: `polish-summaries/webhunt-20260913/hunt/`

### Gate (web)

### tsc: ok
```

```
### lint: ok
```
✔ No ESLint warnings or errors
```
### build: ok
```
├ ○ /privacy                             146 B           102 kB
├ ƒ /restaurant/[id]                     11.8 kB         113 kB
├ ○ /search                              6.7 kB          114 kB
├ ○ /seller                              6.04 kB         109 kB
├ ○ /seller/deals                        5.84 kB         109 kB
├ ○ /seller/login                        1.52 kB         104 kB
├ ○ /seller/menu                         8.45 kB         111 kB
├ ○ /seller/onboarding                   6.99 kB         101 kB
├ ○ /seller/orders                       3.53 kB         106 kB
├ ƒ /seller/orders/[id]                  8.09 kB         111 kB
├ ○ /seller/settings                     6.72 kB         110 kB
├ ○ /support                             147 B           102 kB
└ ○ /terms                               147 B           102 kB
+ First Load JS shared by all            87.3 kB
  ├ chunks/2117-c6c0f3e875b3b048.js      31.7 kB
  ├ chunks/fd9d1056-6c04e91e0db6fb3a.js  53.7 kB
  └ other shared chunks (total)          1.95 kB


○  (Static)   prerendered as static content
ƒ  (Dynamic)  server-rendered on demand

Browserslist: browsers data (caniuse-lite) is 6 months old. Please run:
  npx update-browserslist-db@latest
  Why you should do it regularly: https://github.com/browserslist/update-db#readme
```

## uiux — `ke_uiux_web`

Findings across all rounds, severity descending:

| Sev | Round | Finding | Status |
|---|---|---|---|
| 6 | 2 | Half of all buttons skip the contract's .focus-ring (123/227), including every seller order-state button and modal close/stepper control | FIXED |
| 5 | 1 | No focus-visible styling anywhere in web/ — buttons, chips, and btn-primary links fall back to the browser's blue UA ring | FIXED |
| 5 | 1 | text-dark-500 helper copy fails AA contrast — 97 uses, ~40 of them at text-xs/text-sm | FIXED |
| 5 | 2 | Restaurant detail page never renders the cover photo or menu item photos that sellers are required to upload | FIXED |
| 5 | 3 | Meat/Dairy/Pareve item badge implemented three ways with divergent shape, size, and tint | FIXED |
| 5 | 3 | Kosher filter bottom sheet is the only modal with no dialog semantics | FIXED |
| 4 | 1 | Error banner hand-rolled ~28 times in 5 recipes; only 9 of 86 danger messages have role="alert" | FIXED |
| 4 | 2 | text-dark-500 captions fail WCAG AA on every canvas surface (3.2–4.2:1) — ~30 real text instances, mostly 12px | FIXED |
| 4 | 3 | `scheduled` order status is the last raw Tailwind hue (sky-*), blocking M3 = 0 | FIXED |

Round summaries: `polish-summaries/webhunt-20260913/uiux/`

### Gate (web)

### tsc: ok
```

```
### lint: ok
```
✔ No ESLint warnings or errors
```
### build: ok
```
├ ○ /privacy                             146 B           102 kB
├ ƒ /restaurant/[id]                     11.8 kB         119 kB
├ ○ /search                              9.22 kB         114 kB
├ ○ /seller                              6.02 kB         109 kB
├ ○ /seller/deals                        5.79 kB         109 kB
├ ○ /seller/login                        1.49 kB         104 kB
├ ○ /seller/menu                         8.54 kB         111 kB
├ ○ /seller/onboarding                   6.99 kB         101 kB
├ ○ /seller/orders                       3.53 kB         106 kB
├ ƒ /seller/orders/[id]                  8.08 kB         111 kB
├ ○ /seller/settings                     6.72 kB         110 kB
├ ○ /support                             147 B           102 kB
└ ○ /terms                               147 B           102 kB
+ First Load JS shared by all            87.3 kB
  ├ chunks/2117-c6c0f3e875b3b048.js      31.7 kB
  ├ chunks/fd9d1056-6c04e91e0db6fb3a.js  53.7 kB
  └ other shared chunks (total)          1.95 kB


○  (Static)   prerendered as static content
ƒ  (Dynamic)  server-rendered on demand

Browserslist: browsers data (caniuse-lite) is 6 months old. Please run:
  npx update-browserslist-db@latest
  Why you should do it regularly: https://github.com/browserslist/update-db#readme
```

## backend — `ke_bugs_backend`

Findings across all rounds, severity descending:

| Sev | Round | Finding | Status |
|---|---|---|---|
| 7 | 1 | DeleteAccount strands paid orders when a courier or seller deletes their account | FIXED |
| 7 | 1 | Uber Direct CreateDelivery sends no idempotency_key — a retry after a lost response buys a second paid courier | FIXED |
| 7 | 3 | DoorDash 409 duplicate_delivery_id on create is never reconciled — a lost create response ends in a double delivery | FIXED |
| 6 | 2 | Closed restaurants accept charges server-side — charged, then auto-rejected + refunded 10 minutes later | FIXED |
| 5 | 1 | CreateOrder re-adjudicates the deal after the card is charged → charged-but-no-order on an expiring or deactivated promo | FIXED |
| 5 | 2 | DoorDash re-dispatch after a cancel is a guaranteed 409 — the order burns its attempt budget and leaves the external path | FIXED |
| 5 | 3 | DoorDash webhook branches not scoped to the webhook's own delivery id — stale events for a superseded delivery move/un-dispatch the live one | FIXED |
| 5 | 3 | Orphan-payment sweep vs. late CreateOrder race can refund a fulfilled order | FIXED |
| 4 | 2 | Restaurant minimum order is decorative — rendered to every consumer, editable by sellers, enforced nowhere | FIXED |

Round summaries: `polish-summaries/webhunt-20260913/backend/`

### Gate (backend)

### build: ok
```

```
### vet: ok
```

```
### test: ok
```
?   	github.com/koshereats/backend/cmd/api	[no test files]
?   	github.com/koshereats/backend/internal/background	[no test files]
?   	github.com/koshereats/backend/internal/broker	[no test files]
ok  	github.com/koshereats/backend/internal/config	(cached)
?   	github.com/koshereats/backend/internal/ctxkeys	[no test files]
ok  	github.com/koshereats/backend/internal/database	(cached)
ok  	github.com/koshereats/backend/internal/dispatch	(cached)
ok  	github.com/koshereats/backend/internal/doordash	(cached)
?   	github.com/koshereats/backend/internal/email	[no test files]
ok  	github.com/koshereats/backend/internal/handlers	9.865s
ok  	github.com/koshereats/backend/internal/middleware	(cached)
?   	github.com/koshereats/backend/internal/models	[no test files]
?   	github.com/koshereats/backend/internal/notify	[no test files]
?   	github.com/koshereats/backend/internal/observability	[no test files]
ok  	github.com/koshereats/backend/internal/payments	(cached)
?   	github.com/koshereats/backend/internal/payout	[no test files]
ok  	github.com/koshereats/backend/internal/phone	(cached)
?   	github.com/koshereats/backend/internal/pos	[no test files]
?   	github.com/koshereats/backend/internal/pos/clover	[no test files]
?   	github.com/koshereats/backend/internal/redisclient	[no test files]
ok  	github.com/koshereats/backend/internal/scheduler	(cached)
ok  	github.com/koshereats/backend/internal/shipday	(cached)
?   	github.com/koshereats/backend/internal/sms	[no test files]
?   	github.com/koshereats/backend/internal/storage	[no test files]
?   	github.com/koshereats/backend/internal/uberdirect	[no test files]
```
