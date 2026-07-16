# KosherEats

A kosher food-delivery platform for the frum (observant Jewish) community — **consumer, seller, and courier** apps across web, iOS, and Android, backed by a Go API.

## Why I built it

People in my community keep kosher their whole lives — and still, sometimes someone orders from a place that isn't actually kosher and eats something they'd never knowingly eat. It's a small mistake with a real weight to it, and it happens because the information is scattered and easy to get wrong.

I'm part of this community — I was the cantor at my synagogue — so this isn't a market I researched. It's one I live in. KosherEats exists so that ordering dinner doesn't require double-checking whether a restaurant is trustworthy: the platform only surfaces what belongs there.

## What it is

A three-sided marketplace, each side a real app:

- **Consumer** — browse kosher restaurants, order, track delivery (iOS · Android · web)
- **Seller** — restaurant-side order management (iOS · Android)
- **Courier** — delivery workflow and routing (iOS · Android)

The same platform also runs a **second brand (GreenEats)** — the apps and backend were built to be re-skinned for a different vertical without forking the core, which is why the repo carries both.

## Architecture

```
backend/    Go (chi router, pgx/PostgreSQL, Stripe) — REST API, OpenAPI spec, Dockerized, deploys to Fly.io
web/        Next.js 14 · React 18 · TypeScript
ios/        Native Swift apps — consumer, seller, courier (+ GreenEats variants)
android/    Native Kotlin apps — consumer, seller, courier (+ GreenEats variants)
temporal/   background workflows
docs/       deployment, Firebase, and work-handoff notes
```

- **Backend:** Go + `chi` + `pgx`, Postgres, **Stripe** for payments, an `openapi.yaml` contract, containerized and deployed on **Fly.io**.
- **Data:** PostgreSQL. Firebase handles auth and push notifications only — the domain data lives in Postgres behind the Go API.
- **Clients:** native iOS (Swift) and Android (Kotlin) for all three roles, plus a Next.js web client.

## Running it

```bash
cp .env.example .env          # real secrets are never committed
docker-compose up             # backend + Postgres
# web:     cd web && npm i && npm run dev
# backend: cd backend && go run ./cmd/...
```

See `DEPLOYMENT.md` and `FIREBASE.md` for the full setup.

## What I'd do differently

I built **native** iOS and Android for three roles **and** a second brand — that's a lot of surface area for one person to keep in sync, and features drifted between platforms. If I started over, I'd seriously weigh a shared cross-platform layer for the client apps and reserve native only for the parts that genuinely need it, so a change lands everywhere at once instead of six times.

I'd also lock the API contract (`openapi.yaml`) as the source of truth *earlier* and generate clients from it — a lot of the platform drift came from clients each interpreting the API slightly differently.

## Status

Live at **[koshereats.shop](https://koshereats.shop)**. Real platform, actively built.
