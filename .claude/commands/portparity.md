---
description: Deli-counter parity sweep — port iOS (Swift) screens to Android (Kotlin), matching feature parity
---

Run the **work-queue** "deli counter" to port iOS **(Swift/SwiftUI)** screens to
the Android **(Kotlin)** app, matching feature parity. This repo has three apps —
**consumer, seller, courier** — each with an iOS target (`ios/<app>/.../Views`,
`.../ViewModels`) and an Android target (`android/<app>/app/src/main/java/.../ui/screens`).

`$ARGUMENTS` = which app + screens to port (e.g. "consumer CheckoutView,
OrderTrackingView"). If empty, diff the iOS `Views/` against the Android
`ui/screens/` for the named app and **propose the list of iOS screens with no
Android equivalent** before launching.

1. Build one small item per screen (file-disjoint tickets), each naming the iOS
   source file and the target Android package/path.
2. Launch the queue:
   `Workflow({ name: 'work-queue', args: { items: [...], instruction: "Port this
   iOS SwiftUI screen to the Android app as idiomatic Kotlin (Jetpack Compose),
   matching feature parity: same fields, states, validation, navigation, and API
   calls. Wire it into the Android nav graph. Build the Android module for what
   you touch.", verify: true } })`
3. Surface results **inline, needs-attention first**, sorted by severity.

Keep tickets small and file-disjoint (one screen each) so freed agents pull the
next and never collide. Multiple agents spawn — explicit opt-in run. This
complements the Temporal Orchestrator's continuous parity porting; use it for a
focused, on-demand batch.
