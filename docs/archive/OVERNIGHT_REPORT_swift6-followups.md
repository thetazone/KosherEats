# Overnight report — Swift-6 strict-concurrency follow-ups, consumer iOS

Branch: `chore/swift6-strict-followups` (cut from `chore/ci-courier-and-swift6`) · Date: 2026-09-13
Nothing pushed, nothing deployed. `main` untouched.

Picks up the "Notes for a follow-up pass" list at the end of
`docs/OVERNIGHT_REPORT_ci-courier-swift6.md` and closes it out.

**Result: 30 → 0.** The consumer app target compiles with zero warnings both at
the project's current (minimal) checking level and under app-scoped
`SWIFT_STRICT_CONCURRENCY = complete`.

---

## Warning count, by file

Baseline is the 30-warning survey in the previous report (app target, scoped
`SWIFT_STRICT_CONCURRENCY = complete`). "After" is a **clean** build under the
same scoped setting — `xcodebuild clean` first, 65 app source files recompiled,
so nothing is hidden behind incremental caching.

| file | before | after | fixed in |
|---|---|---|---|
| `Services/Haptics.swift` | 10 | **0** | `55b6bd95` |
| `Services/DeliveryActivityManager.swift` | 5 | **0** | `4f322809` |
| `ViewModels/OrderTrackingViewModel.swift` | 2 | **0** | `bf1d245c` |
| `ViewModels/CheckoutViewModel.swift` | 2 | **0** | `233d3bc1` |
| `Services/APIService.swift` | 2 | **0** | `8638bee7` |
| `Views/Orders/OrdersListView.swift` | 1 | **0** | `3b45dcda` |
| `Views/Profile/ConnectedAccountsView.swift` | 1 | **0** | `3e97ab41` |
| `Views/Profile/AddressFormSheet.swift` | 1 | **0** | `3e97ab41` |
| `Views/Components/RemoteImage.swift` | 1 | **0** | `8638bee7` |
| `AppDelegate.swift` | 1 | **0** | `233d3bc1` |
| **total** | **30** | **0** | |

(The previous report's per-file table listed isolation warnings only and split
the 2 non-exhaustive switches into a footnote; they are folded into their own
files' rows here, which is why `DeliveryActivityManager` reads 5 and
`OrdersListView` 1. The total is the same 30.)

Two entries in the baseline list were a single diagnostic plus its note:
`OrderTrackingViewModel.swift:1:1: add '@preconcurrency' to suppress
'Sendable'-related warnings from module 'ObjectiveC'` was attached to the
`deinit` warning at `:206:9` and disappeared with it.

---

## Per-file changes

### `Services/Haptics.swift` — `55b6bd95`
Annotated the enum `@MainActor`. `UIAccessibility.isReduceMotionEnabled` and
every `UIFeedbackGenerator` subclass are already main-actor isolated in the
UIKit SDK, and a generator has to be prepared and fired on the main thread
regardless. One annotation on the type cleared all 10 warnings; every existing
call site is a SwiftUI view or an `@MainActor` view model, so none needed a hop.

### `Views/Orders/OrdersListView.swift` — `3b45dcda`
`OrderStatusBadge.statusColor` now matches `case .unknown` explicitly.
`OrderStatus` is declared in this same module and carries its own `.unknown`
catch-all, so `@unknown default` — which only covers cases added by *other*
modules — left the switch genuinely non-exhaustive. Same fix already applied to
`OrderDetailView` on the base branch.

### `ViewModels/OrderTrackingViewModel.swift` — `bf1d245c`
The `NotificationCenter` block-observer handle is typed `any NSObjectProtocol`,
which isn't Sendable, so the (always nonisolated) `deinit` couldn't read it.
Boxed in a `private struct ObserverToken: @unchecked Sendable`. The `@unchecked`
is justified at the declaration: the handle is an opaque token and
`removeObserver` accepts it from any thread. The alternative — an async hop out
of `deinit` — would have to capture `self` mid-teardown. Mirrors
`LocationManager.ObserverToken` from the base branch.

### `Services/DeliveryActivityManager.swift` — `4f322809`
Two changes. (1) `@preconcurrency import ActivityKit`: `Activity.update(_:)` and
`end(_:dismissalPolicy:)` are *nonisolated async* — Apple's contract is that you
may call them from any concurrency domain — but the SDK's swiftinterface
declares `Activity` with no `Sendable` conformance, so passing our main-actor
`activity` to them read as "sending a non-Sendable value across an isolation
boundary" at all 4 await sites. Narrower than a retroactive `@unchecked
Sendable` conformance on someone else's non-final generic class. (2) The same
`case .unknown` exhaustiveness fix as `OrdersListView`.

### `Services/APIService.swift` + `Views/Components/RemoteImage.swift` — `8638bee7`
Both hold `private static let` constants inside a `@MainActor` class, so the
statics inherited main-actor isolation and couldn't be read from the off-main
contexts that actually use them. Marked `nonisolated`:

- `APIService.iso8601Fractional` / `.iso8601Plain` are `LockedFormatter`, a
  pre-existing `@unchecked Sendable` box that serialises every access behind an
  `NSLock`. They're read from the `dateDecodingStrategy` closure, which is
  `@Sendable` and may genuinely run off the main actor. `nonisolated` on an
  immutable `let` of a Sendable type is sound.
- `RemoteImageLoader.maxPixelSize` is an immutable `CGFloat` (trivially
  Sendable) read by the `Task.detached` downsample.

No new `@unchecked Sendable`, no `nonisolated(unsafe)`.

### `Views/Profile/AddressFormSheet.swift` + `Views/Profile/ConnectedAccountsView.swift` — `3e97ab41`
Two nonisolated SDK callbacks were hopping with `Task { @MainActor in }` while
capturing a non-Sendable value.

- **AddressAutocomplete**: `[MKLocalSearchCompletion]` isn't Sendable. The
  completer is created and configured on the main actor in `init` and MapKit
  delivers its delegate callbacks on that same thread, so
  `MainActor.assumeIsolated` states the fact instead of hopping — the house
  bridge, already used for `CLLocationManagerDelegate` in `LocationManager`.
  Dropping the hop also keeps typeahead results in delivery order; with a hop a
  slow early query could land after a later one and repaint stale suggestions.
  **Results are read off `self.completer`, not the callback's parameter** — the
  parameter is nonisolated and `MKLocalSearchCompleter` isn't Sendable, so
  capturing it in the main-actor closure just moves the same "sending" error
  onto the completer. (The first cut of this fix did exactly that and the
  scoped survey caught it: `AddressFormSheet.swift:276:42: sending 'completer'
  risks causing data races`. It is the same trap `LocationManager` hit with its
  own `manager` parameter.)
- **ConnectedAccountsViewModel.connectGoogle**: `GIDSignInResult` isn't
  Sendable. Project the one field the `Task` actually needs — `idToken`, a
  `String` — out of `result` in the callback, and let only that cross.

### `AppDelegate.swift` — `233d3bc1`
Conforming to `UIApplicationDelegate` (a `@MainActor` protocol) makes the class
main-actor isolated, but `UNUserNotificationCenterDelegate` carries no isolation
annotation in the SDK, so the un-annotated witnesses "cross into main
actor-isolated code and can cause data races". Both witnesses are now
`nonisolated`, matching the protocol. Nothing in either body needs the main
actor — `PushEvents` is a nonisolated enum, and the one main-actor call
(`AppRouter`) already hops via `Task { @MainActor in }`, so the hop's timing is
unchanged.

> `@preconcurrency` on the conformance also clears the strict warning, and was
> tried first. **Rejected**: at the project's current (minimal) checking level
> the compiler emits `AppDelegate.swift:14:13: warning: '@preconcurrency' on
> conformance to 'UNUserNotificationCenterDelegate' has no effect` — it would
> trade a strict-only warning for one in the build CI actually runs.

### `ViewModels/CheckoutViewModel.swift` — `233d3bc1`
`StripeAPI.defaultPublishableKey` is an unannotated `@objc public static var` in
`StripeCore` — shared mutable state in a vendored SDK we can't annotate. Both
writes happen from this `@MainActor` type. Added `@preconcurrency import
StripeCore`, the sanctioned way to mark a module as predating strict
concurrency, rather than bolting `nonisolated(unsafe)` onto our own code. Unlike
the `AppDelegate` conformance case, this import is silent at the default
checking level (verified — the clean default build is warning-free).

---

## Anything left

**Nothing.** Zero warnings in the app target under both checking levels.

Three of the ten fixes lean on an explicit escape hatch rather than a
restructuring, and each is recorded here and at its call site so the next reader
doesn't mistake it for an oversight:

| escape hatch | where | why it's the right call | when to revisit |
|---|---|---|---|
| `@preconcurrency import ActivityKit` | `DeliveryActivityManager` | `Activity`'s methods are documented as callable from any domain; the SDK just doesn't declare `Sendable` | ActivityKit annotates `Activity` |
| `@preconcurrency import StripeCore` | `CheckoutViewModel` | vendored SDK global we can't annotate; our writes are main-actor confined | Stripe ships a concurrency-audited `StripeCore` |
| `ObserverToken: @unchecked Sendable` | `OrderTrackingViewModel` | opaque `NotificationCenter` token, `removeObserver` is thread-safe; `deinit` is always nonisolated | `NotificationCenter` handles get a Sendable type |

Not attempted, and deliberately so:

- **`APIService` as an `actor`.** Would be the "purest" answer to its
  main-actor-isolated statics, but it's a cross-cutting restructuring of every
  call site in the app — far beyond this pass. The `nonisolated` + `NSLock`-box
  fix is complete and sound on its own; no warning depends on it.
- **Flipping the project to Swift 6 language mode, or committing
  `SWIFT_STRICT_CONCURRENCY = complete`.** Per the previous report, a
  *project-level* setting is fine (it stops at the `.xcodeproj`'s own targets),
  but passing `SWIFT_STRICT_CONCURRENCY=complete` on the `xcodebuild` command
  line applies it to the whole build graph and breaks the vendored Stripe SDK —
  7 silent `SwiftCompile … failed` errors with no diagnostics. That decision
  belongs to whoever owns the migration, not to a warning-cleanup pass.
- `docs/KE-TODO.md` left unedited, same reasoning as the base branch (avoid
  conflicting with other worktrees).

---

## Gates

Both run against simulator `iPhone 17 Pro`, `xcodebuild clean` first so every
app source file is genuinely recompiled (65 files each run, confirmed via
`SwiftCompile normal arm64 …` lines in the logs).

### Gate 1 — default build settings (what CI runs)

```
xcodebuild build \
  -project ios/consumer/KosherEatsConsumer.xcodeproj \
  -scheme KosherEatsConsumer \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -configuration Debug CODE_SIGNING_ALLOWED=NO
```

Tail, verbatim:

```
Touch /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsConsumer-canhbgxcnvucfvcjrzjelnrhrmyr/Build/Products/Debug-iphonesimulator/KosherEatsConsumer.app (in target 'KosherEatsConsumer' from project 'KosherEatsConsumer')
    cd /Users/samma/projects/Mamiye-Eats/.claude/worktrees/agent-a09c6a6771e2a4e0b/ios/consumer
    /usr/bin/touch -c /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsConsumer-canhbgxcnvucfvcjrzjelnrhrmyr/Build/Products/Debug-iphonesimulator/KosherEatsConsumer.app

** BUILD SUCCEEDED **
```

`EXIT=0` · warnings referencing app sources: **0**.

### Gate 2 — scoped `SWIFT_STRICT_CONCURRENCY = complete`

Same invocation, with `SWIFT_STRICT_CONCURRENCY = complete;` temporarily added
to the two project-level `XCBuildConfiguration` blocks in `project.pbxproj` (the
ones carrying `SWIFT_VERSION = 5.9`, at lines 729 and 813). Scoping it in the
project file rather than on the command line is what keeps the vendored SPM
packages — Stripe above all — out of the strict build.

Tail, verbatim:

```
Touch /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsConsumer-canhbgxcnvucfvcjrzjelnrhrmyr/Build/Products/Debug-iphonesimulator/KosherEatsConsumer.app (in target 'KosherEatsConsumer' from project 'KosherEatsConsumer')
    cd /Users/samma/projects/Mamiye-Eats/.claude/worktrees/agent-a09c6a6771e2a4e0b/ios/consumer
    /usr/bin/touch -c /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsConsumer-canhbgxcnvucfvcjrzjelnrhrmyr/Build/Products/Debug-iphonesimulator/KosherEatsConsumer.app

** BUILD SUCCEEDED **
```

`EXIT=0` · warnings referencing app sources: **0**.

### `project.pbxproj` restored

Restored from a byte-for-byte backup taken before the edit, verified by
checksum, and absent from the branch diff:

```
$ shasum -a 256 -c pbxproj.sha
ios/consumer/KosherEatsConsumer.xcodeproj/project.pbxproj: OK

$ git diff --stat -- ios/consumer/KosherEatsConsumer.xcodeproj/project.pbxproj
(no output)
```

### Branch diff — `git diff --stat chore/ci-courier-and-swift6..HEAD`

Ten files, all `.swift`. **No `project.pbxproj` entry.**

```
 ios/consumer/KosherEatsConsumer/AppDelegate.swift  | 15 +++++++++++++--
 .../KosherEatsConsumer/Services/APIService.swift   | 10 ++++++++--
 .../Services/DeliveryActivityManager.swift         | 17 +++++++++++++++--
 .../KosherEatsConsumer/Services/Haptics.swift      | 10 ++++++++++
 .../ViewModels/CheckoutViewModel.swift             |  8 ++++++++
 .../ViewModels/OrderTrackingViewModel.swift        | 22 +++++++++++++++++-----
 .../Views/Components/RemoteImage.swift             |  4 +++-
 .../Views/Orders/OrdersListView.swift              |  6 +++++-
 .../Views/Profile/AddressFormSheet.swift           | 22 ++++++++++++++++++----
 .../Views/Profile/ConnectedAccountsView.swift      |  7 ++++++-
 10 files changed, 103 insertions(+), 18 deletions(-)
```

Note the ratio: 103 insertions for 18 deletions across ten small fixes. Most of
the added lines are rationale comments at each site, so the next reader doesn't
"simplify" an `assumeIsolated` back into a `Task` hop or drop a
`@preconcurrency` import that looks redundant.

---

## Commits

```
233d3bc1 ios(consumer): clear the last AppDelegate + CheckoutViewModel isolation warnings
3e97ab41 ios(consumer): stop sending non-Sendable values out of SDK callbacks
8638bee7 ios(consumer): mark Sendable static caches nonisolated
4f322809 ios(consumer): clear DeliveryActivityManager isolation + exhaustiveness warnings
55b6bd95 ios(consumer): isolate Haptics to the main actor
3b45dcda ios(consumer): make OrdersListView's OrderStatus switch exhaustive
bf1d245c ios(consumer): box OrderTrackingViewModel's push observer for nonisolated deinit
```
