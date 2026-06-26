# Spikes 011 & 012 — Scoping Findings

> Read-only scoping analysis for two P3 (direction) plans. Deliverable is this
> findings doc for the maintainer to greenlight / defer / drop. No source was
> modified; no PoC code was committed. Investigated off `origin/main` @ `34972494d`.
>
> - Plan 011: `plans/011-spike-platform-neutral-swift-core.md` — extract the
>   platform-neutral Swift core into an SPM target (`ListenUpCore`) for future
>   macOS/watchOS sharing.
> - Plan 012: `plans/012-spike-app-intents-siri-scope.md` — scope App
>   Intents / Siri / Control Center for audiobook listening.

---

## Spike 011 — Platform-neutral Swift core → SPM target

### Current state

The native player already separates platform-neutral logic from SwiftUI/AVFoundation,
which is what makes this spike worth doing at all. `Features/Player/` is 16 files /
~2,900 lines. Splitting them by whether they `import Shared` (the Gradle-generated
Swift Export framework) gives a clean first cut:

**Shared-free today (8 files) — strong extraction candidates:**

| File | Lines | iOS-only deps | Classification |
|---|---|---|---|
| `PlayerGestureMath.swift` | 48 | `import CoreGraphics` only (cross-platform) | (a) **pure — move as-is** |
| `AudioSegment.swift` | 53 | `Foundation` only | (a) **pure — move as-is** |
| `PlayerPhase.swift` | 63 | `Foundation` only | (a) **pure — move as-is** |
| `PositionTracker.swift` | 97 | `Foundation`, `QuartzCore` (cross-platform) | (a) **pure — move as-is** |
| `PlaybackSeam.swift` | 114 | `Foundation`; protocol-only (`@preconcurrency import Shared` for one decl) | (a/b) mostly pure; see note |
| `AudioEngine.swift` | 318 | `AVFoundation`, **`AVAudioSession`** (iOS-only) | (b/c) **`#if os` the session bits** |
| `SystemIntegration.swift` | 136 | `MediaPlayer` / `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` | (c) platform-Now-Playing — split |
| `RoutePickerView.swift` / `PlayerGlyphs.swift` | 22 / 22 | SwiftUI view + glyph constants | (c) **stays in UI layer** |

**Imports `Shared` today (8 files) — entangled, but cleanly so:**
`KotlinPlaybackSeam.swift`, `PlayerCoordinator.swift` (588 lines, the orchestrator),
`FullScreenPlayerView`, `MiniPlayerBar`, `NowPlayingUpNextPanel`, `PlayerSheets`,
`PlayerExpansionOverlay` (all SwiftUI). The five SwiftUI views stay in the iOS UI
layer regardless. The two that matter are `PlayerCoordinator` and `KotlinPlaybackSeam`.

**The architecture is already right.** `KotlinPlaybackSeam.swift` is a textbook
anti-corruption layer: it adapts the Koin-resolved `PlaybackPreparer` into native
value types (`PreparedPlayback`, `PreparedTimeline`, `PreparedFile`) so the rest of
the player "never sees a KMP type" (per the file header). `PlaybackSeam.swift`
defines `PlaybackEngine`, `PlaybackPreparing`, `SleepTiming`, `SkipIntervalProviding`
as platform-neutral protocols; `AudioEngine` is the production conformer,
`FakePlaybackEngine` the test double. The seam protocols are neutral; only the
*KMP-backed implementations* import `Shared`.

Tests already exist for the pure units and use `@testable import ListenUp` +
Swift Testing: `PlayerGestureMathTests`, `PositionTrackerTests`, `AudioSegmentTests`.
These move with their units.

**`ChapterMath` named in the plan does not exist as a standalone type** — chapter
math currently lives inline inside `PlayerCoordinator` and `AudioSegment` (grep finds
references, no `enum ChapterMath`). The plan's suggested PoC unit is therefore *not*
the lowest-friction one; `PlayerGestureMath` or `PositionTracker` are (they're already
discrete enums with passing tests).

### The real blocker: how `Shared` is consumed

This is the finding that drives the recommendation. `Shared` is **not** a normal
SPM dependency. It's a Gradle-generated Swift Export framework, embedded into the
**app target only** by a `PBXShellScriptBuildPhase` that runs
`./gradlew :sharedLogic:embedSwiftExportForXcode` (project.pbxproj line ~335). The
generated package lives at `sharedLogic/build/SPMPackage` and is wired through Xcode's
embed step, not through `Package.swift`.

The existing local SPM package, **`ListenUpActivityKit`, deliberately does NOT import
`Shared`** (verified: zero `import Shared` in the package). It's pure-Swift
(`AudiobookActivityAttributes`, `PlaybackControlling` protocol, the `LiveActivityIntent`s)
precisely because an SPM package target has no established way to link the
Gradle-embedded `Shared` framework. The app target supplies the KMP-backed
implementation (`PlaybackController` conforms to the package's `PlaybackControlling`).

So there are **two tiers** of extraction, with very different costs:

- **Tier 1 — Shared-free core (LOW friction).** `PlayerGestureMath`, `AudioSegment`,
  `PlayerPhase`, `PositionTracker`, the neutral `PlaybackSeam`/`PlaybackPreparing`
  protocols, and `AudioEngine` (with `#if os(iOS)` around the `AVAudioSession` calls)
  can move into a `ListenUpCore` SPM target exactly like `ListenUpActivityKit` already
  does. No `Shared` link needed. Tests move with them.
- **Tier 2 — KMP-backed core (HIGH friction / blocked).** `KotlinPlaybackSeam` and
  `PlayerCoordinator` need `Shared`. To put them in an SPM package you'd have to teach
  the package to find the Gradle-generated framework — either restructure
  `embedSwiftExportForXcode` to expose `Shared` as a package the new target can depend
  on, or keep the KMP-adapter layer in the app target and inject it into the core via
  the existing protocol seam (the `ListenUpActivityKit` pattern, applied to the player).

The second option is the clean one and it's the same anti-corruption pattern already
in use: `ListenUpCore` defines protocols + pure logic + the engine; the app keeps the
thin `Kotlin*` adapters that import `Shared` and injects them. The seam is *already
shaped for this* — `PlaybackSeam.swift` exists for exactly this reason.

### What extraction involves (concrete steps)

1. Create `iosApp/ListenUpCore/` as a local SPM package (mirror `ListenUpActivityKit/Package.swift`,
   `platforms: [.iOS(.v18), .macOS(.v15), .watchOS(...)]`).
2. Move Tier-1 files + their tests into it. Make the moved types `public` (they're
   currently internal — this is the bulk of the mechanical churn).
3. Gate `AudioEngine`'s `AVAudioSession` usage behind `#if os(iOS)` (macOS uses a
   different session model; watchOS differs again). `SystemIntegration`'s
   `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter` is cross-Apple-platform but the
   lock-screen wiring differs — keep the Now-Playing dictionary builder (pure) in core,
   the registration in platform code.
4. Keep `KotlinPlaybackSeam` + `PlayerCoordinator` in the app target; have them depend
   on `ListenUpCore` and inject the `Kotlin*` adapters (Tier-2 stays app-side for now —
   that's the point of the seam).
5. Add the package to `project.pbxproj` as a second `XCLocalSwiftPackageReference`
   (the `ListenUpActivityKit` entry is the exact template) and link it on the app target.
6. Resolve the named **Plan 002 dependency**: the `@unchecked Sendable` conformances on
   `KotlinSleepTiming` / `KotlinSkipIntervalProviding` (KotlinPlaybackSeam.swift) and
   `nonisolated(unsafe)` in `Dependencies.swift`. These are in the *adapter* layer (Tier 2,
   staying app-side), so they don't block the Tier-1 extraction — but they DO block a clean
   `Sendable` core if you later pull the seam in. Note: Plan 002 has not landed.

### Risks & unknowns

- **Public-access churn.** Every moved type and member flips `internal → public`. Mechanical
  but touches every file and its `@testable import` tests (which become `import ListenUpCore`).
- **`@testable` loss.** Tests that reach internals must either keep them `@testable`-able
  inside the package or be rewritten against the public surface. The current pure-unit tests
  test public-ish behavior, so this is low.
- **Build-graph ordering.** The Swift Export Gradle phase already runs as a build phase; a
  new package that does *not* depend on `Shared` is unaffected. A package that *does* would
  need the Gradle phase to produce its framework before the package compiles — unproven, and
  the reason Tier 2 is deferred.
- **No watchOS/macOS target exists yet**, so "it builds for those platforms" can't be
  verified — only "it builds as a platform-neutral package the iOS app consumes." The real
  payoff (a macApp/watchApp consuming the core) is a future plan, not this one.

### Effort: **M** (Tier-1 extraction) / **L** (full seam incl. Tier-2)

Tier-1-only — moving the ~6 pure units + tests into a `ListenUpCore` package the app
consumes, mirroring `ListenUpActivityKit` — is a contained **M**: a day-ish, mostly
access-modifier churn and one `#if os` gate. The full vision (KMP-backed core in the
package, macApp/watchApp consuming it) is **L** and gated on Plan 002 + a Swift-Export
packaging decision.

### Recommendation: **DEFER — but it's a real, well-shaped future, not a dead end.**

The codebase is genuinely close: the seam exists, the pure units are already isolated and
tested, and `ListenUpActivityKit` proves the local-SPM-package pattern works in this project.
**Do not greenlight the full extraction now** — there is no macOS/watchOS target to consume
it, so the work would be structure-for-structure's-sake with no shippable payoff, and the
high-value Tier-2 piece is blocked on the unstarted Plan 002. The right trigger to revive
this is **"we're actually starting a watchApp/macApp"** — at that point extract Tier-1 first
(low risk, immediate), then resolve Swift-Export packaging for Tier-2.

One cheap thing worth doing *independent* of this spike: keep new player logic on the
right side of the seam (pure units Shared-free, KMP behind `Kotlin*` adapters) so the
eventual factoring stays a factoring exercise. That's already the documented rule
(`iosApp/CLAUDE.md` #4); the spike confirms it's being followed.

**Maintainer decisions needed:**
- Is a native macOS/watchOS target on the actual roadmap, or aspirational? The answer flips
  this from "defer" to "do Tier-1 now."
- If/when Tier-2 is wanted: restructure `embedSwiftExportForXcode` to vend `Shared` as a
  package dependency, or keep KMP adapters app-side and inject? (Recommend the latter — it's
  the existing pattern.)

---

## Spike 012 — App Intents / Siri / Control Center

### What exists today (more than the plan assumed)

A substantial App Intents surface is **already shipped**, not stubbed:

| Component | File | Status |
|---|---|---|
| `PlayBookIntent` | `Features/Intents/PlayBookIntent.swift` | **Working** — `@AppIntent(schema: .books.playAudiobook)`, `AudioPlaybackIntent`, `openAppWhenRun`, forwards to `PlaybackControlling.playBook(id:)` |
| `BookEntity` | `Features/Intents/BookEntity.swift` | **Working** — `@AppEntity(schema: .books.audiobook)`, projects `BookListItem` |
| `BookEntityQuery` | `Features/Intents/BookEntityQuery.swift` | **Working** — `EntityStringQuery` backed by offline-first `BookRepository.search` (never-stranded) |
| `ListenUpShortcuts` | `Features/Intents/ListenUpShortcuts.swift` | **Working** — `AppShortcutsProvider` with Siri phrases for play / toggle / skip fwd / skip back |
| `TogglePlaybackIntent`, `SkipForwardIntent`, `SkipBackwardIntent` | `ListenUpActivityKit/.../PlaybackIntents.swift` | **Working** — `LiveActivityIntent`s reused by both the Live Activity and Siri |
| `PlaybackControlling` seam | `ListenUpActivityKit/.../PlaybackControlling.swift` | **Working** — `@MainActor` protocol; app provides `PlaybackController` → `PlayerCoordinator` |
| `AudiobookLiveActivityWidget` | `ListenUpWidgets/AudiobookLiveActivityWidget.swift` | **Working** — Live Activity with play/pause/skip controls |
| Tests | `AppIntentsTests.swift`, `PlaybackIntentsTests.swift` | **Present** (one known timing-flake on the iOS CI lane, per project memory) |

So the **plumbing, the seam, the Siri-phrase surface, the assistant-schema conformance,
and the offline-first entity resolution are all done.** This spike is purely about the
**gap**, not re-proposing shipped work.

### The gap — audiobook-relevant intent surface

| Capability | Trigger type | Shared use-case needed | New contract? | User value | In gap? |
|---|---|---|---|---|---|
| Play `<book>` by name | Siri / Shortcuts | `BookRepository.search` | no | High | ✅ **shipped** |
| Play / pause / skip | Siri / Live Activity | `PlayerCoordinator` via seam | no | High | ✅ **shipped** |
| **Resume my book** (last-played, no name) | Siri / Shortcuts / **Control Center control** | needs "most-recently-played book" read | **maybe** — see below | High | ❌ **gap** |
| **Control Center control** (`ControlWidget`, iOS 18+) — play/pause + resume from CC | Control Center | reuse `PlaybackControlling` + resume use-case | no (reuses) | High | ❌ **gap** |
| **Lock Screen control** (same `ControlWidget` API) | Lock Screen | same as Control Center | no | Medium-High | ❌ **gap** |
| **Set sleep timer** ("sleep timer 30 min") | Siri / Shortcuts | `SleepTiming` seam (exists) | no | Medium | ❌ **gap** |
| **What am I listening to** | Siri | current `PlayerPhase` read | no | Low-Medium | ❌ **gap** |
| Play `<series>` / next in series | Siri / Shortcuts | series→next-book resolution | likely (use-case) | Medium | ❌ **gap** |
| Home Screen / Lock Screen **widget** (now-playing, resume) | Widget (`WidgetKit`) | most-recent-book read + cover | maybe | Medium | ❌ **gap** |

### Recommended first slice: **"Resume" — Control Center control + `ResumePlaybackIntent`**

The highest-value, lowest-cost next step, and it reuses everything already in place:

- **What it is:** an `OpenAudiobookIntent`/`ResumePlaybackIntent` ("Resume my book in
  ListenUp" — no book name required) **plus** a `ControlWidget` (iOS 18 Control Center +
  Lock Screen) with a play/pause + resume button. The Live Activity already proves the
  control pattern; Control Center is the same `LiveActivityIntent`/`AppIntent` machinery
  surfaced through `ControlWidget` instead of `ActivityKit`.
- **What it reuses (no new shared contract for play/pause/skip):**
  - `PlaybackControlling` seam (toggle/skip already wired through `PlayerCoordinator`).
  - `ListenUpShortcuts` (add the new intents to the existing provider — one file edit).
  - The existing `PlaybackController` registration in `ListenUpApp`.
- **The "last-played book" read already exists — NO new contract needed.**
  `HomeRepositoryImpl.getContinueListening(limit)` / `observeContinueListening(limit)`
  (in `:sharedLogic`) returns `ContinueListeningBook`s ordered by `lastPlayedAt`,
  offline-first from the local store. The top item *is* "resume my book." There is also a
  pre-existing `client/voice/VoiceIntentResolver.kt` in `:sharedLogic` worth reading before
  building — it may already model voice-driven resolution the iOS intent can reuse.
  **This removes the only contract risk the plan anticipated:** Resume reuses
  `getContinueListening(1)`, no `:sharedLogic` change, fully consistent with the
  single-source-of-truth / never-stranded rules.

### Constraints / Never-Stranded notes

- **Auth-while-not-signed-in:** intents can fire when signed out. `playBook`/resume must
  fail gracefully (open app to sign-in, not crash). `BookEntityQuery` already degrades to
  `[]` offline; the *playback* intents need an explicit signed-out path.
- **Offline:** entity resolution is already offline-first (local Room FTS5). Resume must
  read the last book from the local store, not the network — consistent with the
  single-source-of-truth rule.
- **Background-launch limits:** `AudioPlaybackIntent`/`openAppWhenRun` handles foreground
  needs; a Control Center toggle that only play/pauses an *already-loaded* engine can run
  without foregrounding, but *starting* playback (resume from cold) may need to open the app.
  Worth validating on-device.
- **Siri phrasing:** "Resume my book in ListenUp" / "Continue my audiobook" — must include
  the `\(.applicationName)` token like the existing phrases.

### Effort: **S**

The "last-played book" read **already exists** (`getContinueListening` /
`observeContinueListening`), so the slice is purely native: a `ControlWidget`
(Control Center + Lock Screen) + a `ResumePlaybackIntent` added to the existing
`ListenUpShortcuts`, reusing `PlaybackControlling` and the existing repository read.
No `:sharedLogic`/contract change. **S** — the seam, schemas, and resume-read are all
in place; this is wiring native iOS 18 surfaces onto shipped plumbing.

### Recommendation: **DO IT — small, high-value, mostly reuses shipped plumbing.**

Unlike 011, this has immediate shippable payoff and almost no architectural risk: the seam,
schemas, and Siri surface are already proven in production. The first slice (Resume +
Control Center control) is a natural, idiomatic iOS 18 extension of what's there, and the
gating question — "is last-played readable?" — **resolved favorably during this spike**:
`getContinueListening` already provides it. This is a clean, no-contract-change spec→plan→build.

**Maintainer decisions needed:**
- Confirm Control Center / Lock Screen controls are in-scope for the first slice (CarPlay
  stays explicitly out per the charter — not included here). No contract decision required;
  the resume-read already exists.

---

## Summary

| Spike | Recommendation | Effort | Why |
|---|---|---|---|
| **011** — platform-neutral SPM core | **Defer** (well-shaped; revive when a watch/mac target is real) | M (Tier-1) / L (full) | No platform to consume it yet; Tier-2 blocked on unstarted Plan 002 + Swift-Export packaging. Architecture is already correct — keep holding the seam line. |
| **012** — App Intents / Siri / Control Center | **Do it** (first slice: Resume + Control Center control) | S | Plumbing/seam/schemas already shipped; the last-played read (`getContinueListening`) already exists — no contract change. Clean spec→plan→build. |
