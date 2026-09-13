# KosherEats Polish — Round 2
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [web] A transient failure of POST /auth/refresh wipes the session and force-logs-out the user (consumer + seller)** — FIXED
  `runRefresh` in both clients wraps the refresh call in a catch-all that clears localStorage on ANY error (web/src/lib/api.ts:97 `clearAuthTokens()`, w
  > No web test suite exists (only `next lint`), so verification is `tsc --noEmit` + `eslint`, both green. Note the diff sta

- **[6/10] [web] Seller area never routes a dead session to /seller/login — expired sellers see raw 'refresh token revoked' errors and a 15 s poll loop** — FIXED
  `sellerApi.isUnauthorized` (web/src/lib/sellerApi.ts:221) is exported but referenced by zero seller pages/components (grep: 0 hits in seller/page.tsx,
  > Done. All changes are under `web/`; nothing committed (working tree also carries the pre-existing uncommitted issue-1 / 

- **[5/10] [web] 'Payment methods' page saves cards that can never be seen, used, or removed on web — misleading dead-end feature** — FIXED
  web/src/app/account/payments/page.tsx tells the user 'Cards you save are available at checkout here and in the app' (line 297) and after a successful 
  > Both `tsc --noEmit` and `eslint` pass (exit 0, no output) on the edited files.

## What I changed (web-only, 3 files)

*
