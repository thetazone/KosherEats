# White-label consolidation plan (koshereats ↔ greeneats)

> D4 deliverable — audit + plan + pilot. **No code merged** (too high-risk to automate);
> this is the validated path for a deliberate, reviewed effort.

## TL;DR

The `greeneats-*` apps are **copy-paste forks** of the `koshereats-*` apps (not config
variants), frozen ~May 31 2026 while koshereats kept evolving to June. They duplicate
roughly **70k+ lines** of client code across iOS + Android with **effectively zero
intentional per-brand logic** — and the fork is already drifting (koshereats gained
features the fork never received). GreenEats also **isn't actually branded yet**: same
orange theme as koshereats, and a **placeholder Firebase project**.

→ Consolidating now (before further drift) is the cheapest it will ever be, and makes
brand drift *structurally impossible* (one source, one fix → both brands).

## What actually differs (the entire brand delta)

| Axis | koshereats | greeneats |
|---|---|---|
| applicationId / bundle id | `com.koshereats.{consumer,seller,courier}` | `com.greeneats.*` |
| App display name | KosherEats / …Seller / …Driver | GreenEats / … |
| Tagline (the *only* real copy diff) | "Delivering kosher, done right" | "Delivering plant-based, done right" |
| Android package root | `com.koshereats.*` | `com.greeneats.*` (mechanical rename only) |
| Firebase (`google-services.json` / `GoogleService-Info.plist`) | real `koshereats-c463e` project | **placeholder** (`greeneats-placeholder`, project_number "0", fake keys) |
| Theme color | Orange `#F97316` | **also Orange** — not differentiated yet |
| API base URL | `koshereats-api.fly.dev` | **same** — not a brand axis |

Everything else is identical modulo a `s/koshereats/greeneats/` rename. The large raw
diff line-counts are **staleness**, not branding (the fork is behind).

## Android plan — one module per role + Gradle product flavors

**Hard constraint:** a `productFlavor` can override `applicationId` but **cannot rewrite
source `package` declarations** (fixed by `android.namespace`). So keep **one canonical
namespace (`com.koshereats.<role>`) for both brands** — the Play Store identity still
differs via per-flavor `applicationId` (Firebase/OAuth key off applicationId, not the
code package). Drop the stale `com.greeneats.*` source.

Steps (do **consumer first** as the pilot — it's near-identical):
1. Keep the koshereats module as the single source of truth (newer, strict superset).
2. Add `flavorDimensions += "brand"` + `productFlavors { koshereats {} ; greeneats {} }`.
3. Move `app_name` / `home_*` / brand color out of shared `strings.xml` into per-flavor
   `resValue(...)` (or `src/<flavor>/res` overlays).
4. Per-flavor `google-services.json` at `src/koshereats/` (real) and `src/greeneats/`
   (placeholder until a real GreenEats Firebase project exists) — the plugin auto-resolves.
5. Keep one `Application`/`MessagingService` class (manifest `android:name` shared).
6. Delete `android/greeneats-{consumer,seller,courier}` once
   `assembleKoshereatsDebug` **and** `assembleGreeneatsDebug` build green.
7. Repeat for seller + courier (only the name suffix + applicationId tail differ).

### Pilot — `android/consumer/app/build.gradle.kts` (does not touch the real greeneats module)
```kotlin
android {
    namespace = "com.koshereats.consumer"   // unchanged; code package for BOTH flavors
    // remove the hardcoded applicationId from defaultConfig (set per flavor below)
    flavorDimensions += "brand"
    productFlavors {
        create("koshereats") {
            dimension = "brand"
            applicationId = "com.koshereats.consumer"
            resValue("string", "app_name", "KosherEats")
            resValue("string", "home_title", "KosherEats")
            resValue("string", "home_tagline", "Delivering kosher, done right")
            buildConfigField("String", "BRAND_PRIMARY_HEX", "\"#FFFF7A1A\"")
        }
        create("greeneats") {
            dimension = "brand"
            applicationId = "com.greeneats.consumer"
            resValue("string", "app_name", "GreenEats")
            resValue("string", "home_title", "GreenEats")
            resValue("string", "home_tagline", "Delivering plant-based, done right")
            buildConfigField("String", "BRAND_PRIMARY_HEX", "\"#FF16A34A\"") // give GreenEats a real green
        }
    }
}
```

## iOS plan — one shared source set per role + brand xcconfig + brand asset catalog

The iOS forks have **diverged more** than Android (the koshereats apps gained features
the fork lacks: consumer +2,077 LOC, seller +2,473 LOC) — so consolidating onto the
koshereats source actually *upgrades* GreenEats for free.

1. Adopt `koshereats/*` as the single `Shared/` Swift source per role; discard the fork.
2. Extract every hardcoded brand value into a `BrandConfig` backed by Info.plist /
   xcconfig: theme colors (`BRAND_PRIMARY_HEX` etc.), `APIService.baseURL`
   (`API_BASE_URL`, keep the DEBUG env override), legal/support URLs, display name.
3. Two app **targets** per role (KosherEats / GreenEats), each = shared source +
   brand xcconfig + a brand asset catalog + a per-brand `GoogleService-Info.plist`.
   XcodeGen `project.yml` generates both from the shared sources list.

### Pilot — brand xcconfig (consumer), shared-source + 2 targets, no forked Swift
```
Brand/koshereats.xcconfig
  BRAND_DISPLAY_NAME        = KosherEats
  PRODUCT_BUNDLE_IDENTIFIER = com.koshereats.consumer
  API_BASE_URL             = https://koshereats-api.fly.dev/api/v1
  BRAND_PRIMARY_HEX        = F97316
  GSI_REVERSED_CLIENT_ID   = com.googleusercontent.apps.189270494419-...
  BRAND_ASSETS             = BrandKosher
Brand/greeneats.xcconfig
  BRAND_DISPLAY_NAME        = GreenEats
  PRODUCT_BUNDLE_IDENTIFIER = com.greeneats.consumer
  API_BASE_URL             = https://koshereats-api.fly.dev/api/v1   # same backend, vertical-scoped
  BRAND_PRIMARY_HEX        = 16A34A
  BRAND_ASSETS             = BrandGreen
```
`Theme/Colors.swift` reads `BRAND_PRIMARY_HEX` from Info.plist; `APIService` reads
`API_BASE_URL`; the rest of the app is brand-agnostic shared code.

## Sequencing & prerequisites (for the reviewed effort)

1. **Android consumer flavor pilot** (lowest risk — near-identical) → verify both
   `assemble*Debug` build → then seller, courier → delete the 3 greeneats Android modules.
2. **iOS consumer** (extract BrandConfig + two targets from shared source) → seller, courier.
3. **Prereqs that are yours, not code:** stand up a **real GreenEats Firebase project**
   (replace the placeholder `google-services.json`/`GoogleService-Info.plist`), and decide
   GreenEats' **actual brand color** (it's orange today). The backend is already shared
   (one API, `vertical` kosher/vegan scopes data) — no backend consolidation needed.

**Payoff:** ~70k+ duplicated client LOC collapses to one source per role; the recurring
"fix landed in koshereats but not greeneats" class (we hit it with the courier chat bug)
becomes impossible.
