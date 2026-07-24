# KosherEats deployment

Step-by-step walkthrough for getting the backend + web apps + iOS builds
into production. Assumes a fly.io deployment because it's fastest to stand
up, but the Dockerfiles work on any container host (Render, Railway,
Fly, DigitalOcean App Platform, a bare VM, etc.).

---

## 1. Prerequisites

- [Fly CLI](https://fly.io/docs/hands-on/install-flyctl/) installed and authenticated (`fly auth login`)
- A registered domain you control (e.g. `koshereats.com`)
- Accounts provisioned for:
  - [Stripe](https://stripe.com) + Stripe Connect enabled
  - Apple Developer (for APNs + App Store)
  - [Checkr](https://checkr.com) for courier background checks (can skip initially, dev stub auto-approves)
  - AWS (S3 bucket for document + image uploads) — or Cloudflare R2, GCS, etc.

---

## 2. Provision shared infrastructure on Fly

```sh
fly postgres create --name koshereats-db --region iad --vm-size shared-cpu-1x --volume-size 10
fly redis create --name koshereats-redis --region iad
```

Keep the connection strings — you'll attach Postgres automatically when you
deploy the API, and Redis needs to be set manually as a secret.

---

## 2b. Environment variables reference

All backend environment variables are documented in `.env.example` at the repo
root. Copy it to `.env` (local dev) or `.env.production` (deploy) and fill in
real values. Below is a summary by category:

| Category | Variable | Required | Notes |
|---|---|---|---|
| **App** | `PORT` | No | Defaults to `8080` |
| | `APP_ENV` | No | Set to `production` in prod for stricter logging |
| | `WEB_URL` | No | Defaults to `http://localhost:3000` |
| | `JWT_SECRET` | **Yes** | Must be set in production; tokens are unsigned if empty |
| **Database** | `DATABASE_URL` | **Yes** | Postgres connection string |
| **Redis** | `REDIS_URL` | **Yes** | Redis connection string |
| **Stripe** | `STRIPE_SECRET_KEY` | **Yes** | Stripe API secret key |
| | `STRIPE_PUBLISHABLE_KEY` | No | Passed to iOS clients |
| | `STRIPE_WEBHOOK_SECRET` | **Yes** | Signing secret for Stripe webhooks |
| **Google Auth** | `GOOGLE_CLIENT_ID` | No | OAuth client ID |
| | `GOOGLE_CLIENT_SECRET` | No | OAuth client secret |
| **Apple Auth** | `APPLE_CLIENT_ID` | No | e.g. `com.koshereats.consumer` |
| | `APPLE_TEAM_ID` | No | Apple Developer Team ID |
| | `APPLE_KEY_ID` | No | Apple key identifier |
| | `APPLE_PRIVATE_KEY` | No | Contents of .p8 key file |
| **APNs** | `APNS_KEY_ID` | No | Apple push key ID |
| | `APNS_TEAM_ID` | No | Apple team ID |
| | `APNS_P8_KEY` | No | Full .p8 key contents |
| | `APNS_BUNDLE_PREFIX` | No | Defaults to `com.koshereats` |
| | `APNS_PRODUCTION` | No | `true` for prod, empty/`false` for sandbox |
| **S3** | `S3_BUCKET` | No | Bucket name for uploads |
| | `S3_REGION` | No | Defaults to `us-east-1` |
| | `S3_PUBLIC_URL` | No | Optional CDN prefix (CloudFront) |
| **Checkr** | `CHECKR_API_KEY` | No | Empty = dev stub (auto-approve) |
| | `CHECKR_PACKAGE` | No | Defaults to `driver_pro` |
| | `CHECKR_WEBHOOK_SECRET` | No | Signing secret for Checkr webhooks |
| **FCM** | `FCM_SERVICE_ACCOUNT_JSON` | No | Full service account JSON string; empty = stub |
| | `FCM_PROJECT_ID` | No | e.g. `koshereats-prod` |
| **Twilio** | `TWILIO_ACCOUNT_SID` | No | Empty = dev stub (fixed OTP code) |
| | `TWILIO_AUTH_TOKEN` | No | Twilio auth token |
| | `TWILIO_VERIFY_SERVICE_SID` | No | Verify service SID |
| **Uber Direct** | `UBER_DIRECT_CLIENT_ID` | No | Empty = stub mode |
| | `UBER_DIRECT_CLIENT_SECRET` | No | |
| | `UBER_DIRECT_CUSTOMER_ID` | No | |
| | `UBER_DIRECT_WEBHOOK_SECRET` | No | |
| **DoorDash** | `DOORDASH_DEVELOPER_ID` | No | Empty = stub mode |
| | `DOORDASH_KEY_ID` | No | |
| | `DOORDASH_SIGNING_KEY` | No | |
| | `DOORDASH_WEBHOOK_SECRET` | No | |

---

## 3. Deploy the backend API

```sh
cd backend

# First-time launch. Pick "iad" region, no Postgres (we created one), no
# Redis (same), keep existing fly.toml.
fly launch --no-deploy

# Attach the managed Postgres. This auto-writes DATABASE_URL into your app's
# secret store.
fly postgres attach koshereats-db --app koshereats-api

# Copy .env.example to .env.production, fill in real values, then pipe the
# whole file into fly secrets. It handles the `KEY=value` format natively.
cp ../.env.example ../.env.production
# Edit ../.env.production in your editor of choice
fly secrets import --app koshereats-api < ../.env.production

# Deploy. The Dockerfile already handles migrations via main.go's RunMigrations.
fly deploy
```

Verify the API is alive:

```sh
curl https://koshereats-api.fly.dev/health
# → {"status":"ok"}
```

---

## 4. Deploy the web app

The web app is one Next.js project that serves both the consumer-facing
restaurant discovery pages and the `/admin` dashboard.

```sh
cd web

fly launch --no-deploy
fly secrets set NEXT_PUBLIC_API_URL=https://koshereats-api.fly.dev/api/v1 --app koshereats-web
fly deploy --build-arg NEXT_PUBLIC_API_URL=https://koshereats-api.fly.dev/api/v1
```

Then point your domain's DNS at Fly:

```sh
fly certs create koshereats.com --app koshereats-web
fly certs create www.koshereats.com --app koshereats-web
# Follow the CNAME/A record instructions Fly prints.
```

The admin lives at `https://koshereats.com/admin/login`. Seed a real admin
user (not the dev placeholder) before first login:

```sh
fly postgres connect --app koshereats-db
# In psql:
INSERT INTO users (email, password_hash, first_name, last_name, phone, role)
VALUES ('you@koshereats.com',
        '<bcrypt hash — generate with `htpasswd -bnBC 12 "" yourpassword | tr -d ":"`>',
        'Your', 'Name', '+1...', 'admin');
```

---

## 5. Stripe Connect setup (one-time)

Courier payouts depend on Stripe Connect. In the Stripe Dashboard:

1. **Enable Connect** (Settings → Connect settings → enable Express accounts)
2. **Set the product description** and branding — couriers see these during onboarding
3. **Create an API key** in test mode first, swap to live mode before launch
4. **Add a webhook endpoint**: `https://koshereats-api.fly.dev/api/v1/webhooks/stripe`, subscribe to `account.updated` and `payment_intent.succeeded`
5. **Copy the webhook signing secret** into `STRIPE_WEBHOOK_SECRET`

Then:

```sh
fly secrets set STRIPE_SECRET_KEY=sk_live_... STRIPE_PUBLISHABLE_KEY=pk_live_... STRIPE_WEBHOOK_SECRET=whsec_... --app koshereats-api
```

---

## 6. APNs setup (iOS push notifications)

Apple Developer portal:

1. **Keys → Create a Key** with the "Apple Push Notifications service (APNs)" capability
2. **Download the .p8 file** — you only get one chance
3. **Note the Key ID** (the 10-char alphanumeric shown on the key page)
4. **Note your Team ID** (top right of the developer portal)

Load into Fly as multi-line secret:

```sh
# macOS: base64 the key to preserve newlines, or use `fly secrets set` with -e flag
fly secrets set APNS_TEAM_ID=ABC123DEFG APNS_KEY_ID=XYZ890 --app koshereats-api
fly secrets set APNS_P8_KEY="$(cat AuthKey_XYZ890.p8)" --app koshereats-api
```

---

## 7. S3 setup (document + menu image uploads)

```sh
# Create bucket
aws s3 mb s3://koshereats-uploads --region us-east-1

# Apply CORS so iOS clients can PUT directly (presigned URLs)
cat > /tmp/cors.json <<EOF
{"CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["PUT","GET"],"AllowedHeaders":["*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3000}]}
EOF
aws s3api put-bucket-cors --bucket koshereats-uploads --cors-configuration file:///tmp/cors.json

# Create an IAM user with PutObject on this bucket, get access keys
```

Then:

```sh
fly secrets set S3_BUCKET=koshereats-uploads S3_REGION=us-east-1 AWS_ACCESS_KEY_ID=AKIA... AWS_SECRET_ACCESS_KEY=... --app koshereats-api
```

Optional: front the bucket with CloudFront and set `S3_PUBLIC_URL` to the
distribution domain for faster reads + better caching.

---

## 8. Checkr setup (courier background checks)

1. Sign up at [checkr.com](https://checkr.com)
2. Pick a background check package (the cheapest tier works for MVP)
3. Copy the API key from **Settings → Credentials**
4. Add webhook: `https://koshereats-api.fly.dev/api/v1/webhooks/checkr`, subscribe to `report.completed`

```sh
fly secrets set CHECKR_API_KEY=... --app koshereats-api
```

**Note:** The current `internal/background/checkr.go` has a dev stub that
auto-approves couriers after 2s. Before real launch, implement the two
outbound calls in `InitiateCheck` (Candidate + Invitation creation).
There's a `TODO` block in the file marking exactly where.

---

## 9. iOS app store submission

See the per-app checklists:
- `ios/consumer/SUBMISSION_CHECKLIST.md`
- `ios/seller/SUBMISSION_CHECKLIST.md`
- `ios/courier/` — no checklist yet, same pattern as the other two

Before first submission for each app:

1. Set `DEVELOPMENT_TEAM` in `project.yml` to your Apple Developer Team ID
2. Regenerate: `xcodegen generate`
3. Archive in Xcode: `Product → Archive`
4. Upload via Organizer → Distribute App → App Store Connect
5. Fill out App Privacy in App Store Connect to match `PrivacyInfo.xcprivacy`
6. Submit for review

Replace the placeholder icons (orange K, blue S, courier variant) with
real designer artwork before launch. TestFlight is fine with placeholders.

---

## 10. Post-deploy smoke test

Once everything is up:

```sh
# Health check
curl https://koshereats-api.fly.dev/health

# Register, login, browse
TOKEN=$(curl -s -X POST https://koshereats-api.fly.dev/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"smoke@example.com","password":"smokepass","first_name":"Smoke","last_name":"","phone":"+10000"}' \
  | jq -r .token)

curl https://koshereats-api.fly.dev/api/v1/restaurants/ -H "Authorization: Bearer $TOKEN"
```

Check logs:

```sh
fly logs --app koshereats-api
fly logs --app koshereats-web
```

---

## 11. Ongoing operations

**Monitoring.** Fly exposes Grafana dashboards automatically at
`https://fly-metrics.net`. For app-level errors add a Sentry DSN and uncomment
the Sentry init line in `main.go` (currently stubbed behind structured logging).

**Database backups.** Fly Postgres takes daily snapshots. For anything
critical, add a `pg_dump` cron via `fly ssh console`.

**Scaling up.** When a single VM isn't enough:

```sh
fly scale count 2 --app koshereats-api
# Or vertically:
fly scale vm shared-cpu-2x --app koshereats-api
```

The scheduler in `internal/scheduler/dispatcher.go` is NOT safe to run on
multiple replicas as-is — it would double-dispatch scheduled orders. If you
scale the API horizontally, wrap the sweeper in a Postgres advisory lock or
move it to a dedicated singleton worker.

**Migrations.** Just commit new `.sql` files in `backend/internal/database/migrations/`
with the next sequential number — the runner applies them automatically on
the next deploy boot. `dev_*.sql` files are skipped.

**Secrets rotation.** For JWT_SECRET rotation, deploy a new secret alongside
the old one (dual-key verify), let old tokens expire (default 15min access +
7day refresh), then remove the old key. The current code doesn't support
dual-key verification — add before your first rotation.
