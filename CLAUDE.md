# KosherEats (KE) — project state & handoff

> Living progress doc so any Claude session (incl. on the Mac Studio) can resume.
> Last updated: 2026-06-20.

## ✅ DONE (2026-06-20) — both remaining steps completed
The fly web deploy and the git commit are both finished. Nothing pending here.

1. **fly web deploy — LIVE.** `koshereats-web` deployed (2 machines in `iad`, image
   `deployment-01KVHE29…`). Serving at `https://koshereats-web.fly.dev/` and `/search`
   (verified HTTP 200; `/search` renders Holon + Subsational, both with R2 `image_url` covers).
   - **Build fix:** the stalled build was a missing `web/.dockerignore` — `COPY . .` clobbered
     the linux `npm ci` `node_modules` with the host's arm64 `@next/swc` binary, breaking
     `next build` on the remote amd64 builder. Added `.dockerignore` (excludes
     `node_modules`/`.next`/env); build context dropped to 1.49 MB and it built clean.
   - No build arg needed: `NEXT_PUBLIC_API_URL` defaults to `https://koshereats-api.fly.dev/api/v1`.

2. **Commit — DONE.** `fix/koshereats-backend-review` @ `1dbb4a43` (8 files, +392/-312).
   `.env.bak` (old secrets) and `docker-compose.override.yml` (local-only) intentionally left
   untracked. Not pushed yet — push when ready: `git push -u origin fix/koshereats-backend-review`.

Confirmed live: both restaurants on the fly API (`https://koshereats-api.fly.dev/api/v1/restaurants`),
the LOCAL storefront (`http://100.107.132.23:3100/search`), AND the fly web app (above).

## What this is
KosherEats = kosher food/grocery delivery marketplace.
- **Backend (the real API):** `~/Projects/Mamiye-Eats/backend` — Go, module `github.com/koshereats/backend`. Deployed on fly as app **koshereats-api** (`https://koshereats-api.fly.dev`).
- **Web (consumer):** `~/Projects/Mamiye-Eats/web` — Next.js. fly app **koshereats-web** (region iad).
- **DB:** fly app **koshereats-db** (unmanaged Postgres). DB name is **`koshereats_api`** (NOT "koshereats"), user `koshereats_api`. Connect: `flyctl postgres connect -a koshereats-db -d koshereats_api`.
- **Scraper / importer:** `~/Projects/ubereats-import/` (Python, .venv).
- NOTE: `~/Projects/storetok` / `storetok-p0` are a SEPARATE product (HoneyOcean seller app) — not KE.

## CURRENT STATUS (2026-06-19)
Two real restaurants onboarded end-to-end (scraped from UberEats → images on Cloudflare R2 → approved/visible):
- **Subsational Coney Island** — 84 items (71 photos). UberEats: subsational-coney-island-coney-island-ave/eZkByx6vUumsJ8rljz2bvQ
- **Holon Kosher Grocery** — ~2,738 items (with photos). 527 Kings Highway, Brooklyn.

Where they live:
- **Local stack (Studio docker):** both imported + approved. Viewable at `http://100.107.132.23:3100` (web `mamiye-eats-web:local` → local api :8080). postgres on host :5433 (override avoids storetok-p0's :5432).
- **fly production:** both imported + approved; live in `https://koshereats-api.fly.dev/api/v1/restaurants` (alongside 8 demo seeds).
- **fly web (koshereats-web):** LIVE (2026-06-20) — deployed, 2 machines in `iad`, serving `https://koshereats-web.fly.dev/search` with Holon + Subsational. (Earlier stall was a missing `web/.dockerignore`; fixed — see top.)

## Onboarding runbook (repeat for any UberEats restaurant)
From `~/Projects/ubereats-import` (`source .venv/bin/activate`):
1. **Login once/session:** `python login_helper_chrome.py "<store-url>"` — opens REAL Chrome (channel="chrome", automation flag stripped, profile `~/.ubereats-import-chrome`); log in with EMAIL/PHONE (not Google), set a delivery address near the store so the menu loads; it auto-closes.
2. **Scrape + download images:** `python scrape_to_menu.py "<store-url>" output_<name>` — virtualization-aware DOM scrape (UberEats has no clean JSON catalog / no __NEXT_DATA__; menu = `[data-testid=catalog-section-title]` + `store-catalog-section-vertical-grid`, rendered only on scroll). Writes `output_<name>/menu.json` + `images/`.
3. **Config:** copy `subsational.json` → `<name>.json`; edit merchant/restaurant (name/address/cert/cuisine/email/phone) + `branding.cover`/`certificate` (point at a real local image; required by API).
4. **Import** (local OR fly):
   `python to_koshereats.py --api <BASE> --seller <name>.json --menu output_<name>/menu.json --images-dir output_<name>/images --creds output_<name>/creds.txt --imported output_<name>/imported.json`
   - BASE local: `http://localhost:8080` ; fly: `https://koshereats-api.fly.dev`
   - If account already exists, point `--creds` at the file holding its password (e.g. Holon's original `output/merchant_credentials.txt`), else 401.
5. **Approve + locate** (API forces new restaurants to pending/inactive; geocoding is mocked → set lat/lng):
   `UPDATE restaurants SET approval_status='approved', is_active=true, is_open=true, lat=..., lng=... WHERE name='...';`
   - local: `docker compose exec -T postgres psql -U postgres -d koshereats -c "..."` (from `~/Projects/Mamiye-Eats`)
   - fly: `printf "%s\n" "<SQL>" | flyctl postgres connect -a koshereats-db -d koshereats_api`

## Cloudflare R2 (image storage)
Bucket `koshereats-uploads`. Public: `https://pub-7bf58359374f4c4391d337bc0ac50084.r2.dev`. S3 endpoint: `https://acb853aefe07e141bbea239b895ca6c4.r2.cloudflarestorage.com`.
- Local: in `~/Projects/Mamiye-Eats/.env` (S3_BUCKET, S3_ENDPOINT, S3_PUBLIC_URL, S3_REGION=auto, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY).
- fly: set as koshereats-api secrets. **GOTCHA:** config.go prefers `AWS_ENDPOINT_URL_S3` over `S3_ENDPOINT` and `BUCKET_NAME` over `S3_BUCKET`. fly had a leftover Tigris `AWS_ENDPOINT_URL_S3` → had to override it to the R2 endpoint or uploads 403 InvalidAccessKeyId.
- Backend patched to allow **image/webp** (`uploads.go` allowlist + `s3.go` extFromContentType) — UberEats serves webp. Redeploy backend after changes: `cd backend && flyctl deploy -a koshereats-api --remote-only` (Studio is arm64; MUST use remote builder for fly amd64).

## Web app notes
- `src/app/restaurant/[id]/page.tsx`, `src/app/search/page.tsx`, `components/restaurant/RestaurantCard.tsx` are WIRED to the real API (`src/lib/api.ts`) and render R2 images. (They were hardcoded mocks before 2026-06-19.) Homepage `/` is marketing only; browse is `/search`.
- `NEXT_PUBLIC_API_URL` is baked at BUILD time. If unset it defaults to `https://koshereats-api.fly.dev/api/v1` (perfect for fly web). For a LOCAL-pointed web, build with `web/Dockerfile.local` + `--build-arg NEXT_PUBLIC_API_URL=http://<host>:8080/api/v1`.
- CORS (backend `cmd/api/main.go`) allows `http://localhost:3000` + `WEB_URL`. Set `WEB_URL` to the web origin or browser API calls 403.

## Placeholders to replace for real production
- Phone numbers are fake (+1-718-555-xxxx); `kosher_certification` is just "Kosher" with NO real cert doc; covers are a menu photo; ratings/reviews/delivery fields were set to dummy values for looks. Replace per real merchant.
- Single flat category ("Menu"/"Grocery Items") — scraper captures real sections but importer doesn't split them yet.
- Approval + geocoding are manual. No web admin UI; `ADMIN_PASSWORD` fly secret is NOT the login password (admin API login fails) — use the DB for approvals.

## Environment gotchas (Studio over SSH)
- Non-interactive SSH PATH is minimal — use full paths or `export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH`. `docker`=/usr/local/bin/docker, `flyctl`=/opt/homebrew/bin/flyctl.
- `docker compose` needs `docker-credential-desktop` on PATH (in /usr/local/bin).
- Don't `scp` to a path with `[id]` brackets — scp to a temp name then `mv` with single-quotes.
- flyctl auth: `flyctl auth login` (opens browser on the Studio screen).
