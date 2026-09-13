# webhunt-20260913 — web-first pre-merge campaign

Branch `feat/web-hunt-20260913` (stacked on `feat/web-ordering-rubric-sweep-m2`). Nothing pushed, nothing deployed. Generated 2026-09-13 12:35.

## Phases

| Phase | Area | Rounds | Fixed | Gate | Commit |
|---|---|---|---|---|---|
| hunt | `web` | 4 | 12 | GREEN | `eefef7b7` |

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
