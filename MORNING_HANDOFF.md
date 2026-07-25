# Handoff — koshereats.shop ordering + selling campaign (2026-07-17)

**Validation gate: `npx tsc --noEmit` ok · `npm run lint` ok · `npm run build` ok.**
Nothing pushed, nothing deployed. All work is local, on three branches.

## Branch map (review + deploy order)

| Branch | What | Deploy dependency |
|---|---|---|
| `feat/web-ordering-selling` | The campaign: consumer ordering parity + full seller portal + 3 UI/UX passes + 11 debug fixes + consumer geocoding (20 commits) | None — ships alone |
| `feat/seller-latlng-api` | Backend: optional lat/lng on seller restaurant Create/Update (1 commit, `78808ba9`, go build/vet/test pass) | None — ships alone; fixes the NYC-center coordinate bug |
| `feat/web-seller-geocode` | Seller onboarding/settings send geocoded coords (branched off the web branch) | **Merge only after `feat/seller-latlng-api` is deployed** — otherwise the UI collects coords the API discards |

## What was fixed in the debug cascade (severity-sorted)
Fixed in-cascade: double-charge window (8), silent cart wipe on cross-restaurant add (7), reorder cart-wipe + dropped modifiers (7+7), pickup orders stuck Active (7), processing-payment charge-with-no-order (6).
Fixed in the residual pass: cart hydration (found 5×), pending-order save ordering, checkout modal close mid-payment, wrong address auto-select, 45-min schedule floor, token-refresh race, stranded-charge recovery, receipt discount/tip rows, Rate-Courier re-prompts + self-delivery gating, pickup mislabels, chat poll clobber, header badge flash + dead address pill, dead social buttons removed, HEIC cert-upload rejection, stale cert-chip cache, filter Apply trap.
Deliberately NOT done: saved-card selection at checkout (backend payment-intent response lacks customer/ephemeral-key context for PaymentElement — see `logs` in the session for what the backend would need).

## Geocoding
- `web/src/app/api/geocode/route.ts` — server-side US Census proxy (no key, no CORS issue, 24h cache header). Swap point for a paid provider later.
- `web/src/components/ui/AddressGeocodeField.tsx` — find-coordinates-from-address + manual lat/lng fallback (null-island validation preserved).
- Wired into consumer cart address form now; seller forms wired on `feat/web-seller-geocode` (gated, see table).

## Your smoke-test checklist (the only remaining gate)
1. `cd web && npm run dev` — sign up fresh: email OTP delivery, phone OTP, the 403→verify→resume-checkout path
2. Stripe test card (4242…) checkout: order lands, receipt sums (subtotal/fees/tax/tip/discount = total)
3. Order tracking page: status timeline + SSE courier stream in a real browser
4. Seller: onboarding with a photo (try an iPhone HEIC), menu CRUD, accept→ready an order
5. Push when satisfied: `git push -u origin feat/web-ordering-selling` (then the backend branch whenever)

## Ops notes
- `claude` CLI OAuth is dead → `claude login` restores it; the Temporal Cloud lane (namespace `koshereats.fnvcs`, dashboard koshereats.fnvcs.web.tmprl.cloud) is provisioned, tested, and now protected by auth preflights + a fast-fail circuit breaker in the orchestrator.
- Worktree for the seller-geocode branch still mounted in the session scratchpad (`wt-seller-geocode`) — remove with `git worktree remove` after merge, or leave for follow-ups.
- Round-by-round detail: `polish-summaries/` in this repo.
