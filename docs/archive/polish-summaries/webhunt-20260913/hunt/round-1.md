# KosherEats Polish — Round 1
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [web] fetchAPI drops the HTTP status; 10 duplicated substring `isUnauthorized` helpers miss real 401 bodies, breaking sign-out routing and the charged-but-no-order recovery path** — FIXED
  `fetchAPI` (web/src/lib/api.ts:155) and `sellerFetch` (web/src/lib/sellerApi.ts:210) throw `new Error(body.error)`, discarding `res.status` and the pa
  > Done. All changes are under `web/`; `tsc --noEmit` and eslint are clean on every touched file (no web test suite exists)

- **[5/10] [web] Silent refresh never reaches page state, so after 15 min every consumer request becomes 401 → POST /auth/refresh → replay, indefinitely** — FIXED
  `fetchAPI` takes the access token as an explicit `token` option and, on a 401, runs the single-flight refresh, writes the new token to localStorage, a
  > Both clean. 

## Summary

**Root cause:** every consumer page reads `localStorage.token` once on mount and holds it in R

- **[4/10] [web] Open/closed toggle and delivery-mode selector can be reverted by an in-flight 15 s poll (no request-sequence bump on mutation)** — FIXED
  `SellerDashboardPage.load()` (seller/page.tsx:78-100) guards stale poll responses with `requestSeq`, and `runOrderAction` relies on the follow-up `voi
  > Type check passes clean.

Fixed both mutations in `web/src/app/seller/page.tsx`: `toggleOpen` and `setDeliveryMode` now 
