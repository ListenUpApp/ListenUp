# CLAUDE.md — iOS App (`iosApp`)

Operational rules for building the native iOS client. These **override** any
Android-shaped assumption inherited from the root `CLAUDE.md` when working under
`iosApp/`. The *why* behind each rule, and the shared/native boundary, are described
inline below.

> **Mental model:** iOS is its own app. It shares ListenUp's *brain* (domain, data, sync,
> contract) and builds its own *body* (presentation + UI) the way Apple intends. We
> reference Android for implementation clues — never for look, structure, or standards.

## Philosophy & parity

1. **Parity with Android is a guide, not a requirement.** Implement what makes sense for
   iOS; add where it helps, remove or fold where the platforms diverge. Parity informs
   priorities — it does not mandate 1:1 coverage.
2. **Each platform is built the best, most idiomatic way for itself.** A first-class native
   experience outranks code-sharing. Sharing is the means, not the goal.
3. **Reference Android for implementation clues only** — never look, structure, or
   standards. New screens, or screens folded into sheets, as iOS requires.

## Architecture & the sharing boundary

4. **Share the core, build the surface native — and the surface is two layers.**
   - *Shared Kotlin core (`sharedLogic`/contract):* domain models, repositories, sync engine,
     `AppError`, local-DB source-of-truth, RPC contract.
   - *Shared Swift core (Apple-platform-agnostic):* native `@Observable` state objects, the
     KMP/Swift Export seam, and platform-neutral logic (`AudioEngine`, position math, segment/chapter
     math). No `import UIKit`-only assumptions here; gate anything platform-specific behind
     `#if os(...)`. **This layer is destined to be shared with macOS and watchOS** (see below).
   - *Platform-specific UI & integration:* SwiftUI views tuned per platform, plus
     platform-only surfaces (Live Activities, lock screen, menu-bar/Now Playing variants).
   - The Kotlin MVI `*ViewModel`s are **optional** on iOS. Bind one directly only when
     that's genuinely cleanest; otherwise wrap shared logic in a native `@Observable`.
     `Features/Player` is the reference precedent — only `PlayerCoordinator` imports `Shared`,
     and its `AudioEngine`/math units are already platform-neutral.

   **Multi-Apple-platform readiness (forward-looking, structure now):** iOS is the priority,
   but we will later split the native codebase to support **macOS and watchOS natively**.
   Structure everything so that split is a factoring exercise, not a rewrite: keep the shared
   Swift core free of iOS-only dependencies, isolate platform-specific code behind `#if os(...)`
   or per-platform files, and prefer organizing reusable native code so it could move into a
   Swift package/framework target consumed by `iosApp`, a future `macApp`, and `watchApp`.
   Don't build macOS/watchOS UI yet — just don't bake in assumptions that block it.
5. **The local DB is the single source of truth (non-negotiable).** iOS reads
   the shared offline-first store, never the network directly. "Build it like an iOS app"
   governs presentation — it is never license to fork a parallel native networking/cache path.
6. **Diverge in presentation, converge in contract.** If an iOS need reveals a missing
   field or use-case, change it in `sharedLogic`/contract (breaking both sides at compile
   time — the point of the shared contract). Never fork a native-only data path to dodge it.

## iOS idiom & tech

7. **iOS 26 minimum. Swift 6 with strict concurrency. Use the newest platform APIs the
   floor unlocks.** Concurrency conventions:
   - No `nonisolated(unsafe)` shared mutable state. Isolate it (`@MainActor`) or guard it
     (`OSAllocatedUnfairLock`).
   - Funnel framework callbacks (KVO, time observers, notifications) through a **single
     ordered `AsyncStream`** the owning actor drains — not an unordered `Task`-per-callback
     (that reorders).
   - Any `MainActor.assumeIsolated` must document the invariant that makes it sound.
8. **Observation, not Combine.** `@Observable`/`@Bindable`; no `ObservableObject`/`@Published`
   in new code. Bridge Kotlin `Flow`/suspend → Swift `async`/`AsyncSequence` via Swift Export
   (`FlowBridge` is the established helper).
   - **Never feed Swift-Export-bridged Kotlin objects into a `ForEach`/`List`/`LazyVGrid`/`LazyVStack`.**
     Map them to a **native Swift value type at the `@Observable` observer boundary** (in `apply`)
     and feed those structs to the view. Every SwiftUI diff/layout/`scrollTo` re-reads a bridged
     object's properties across the Kotlin boundary — re-bridging per diff (`toKStringFromUtf16`)
     froze the library grid on large data. Precedents: `BookRow`, `SeriesRow`, `ContributorRow`,
     `SearchRow`, `RoleSectionRow`, `EditableRelation`. If the observer still needs the raw Kotlin
     object (e.g. for an id→object lookup on a remove/tap action), keep it as private observer
     state, off the diff path — never in the `ForEach`.
   - **Never `await` an `AppResult`-returning Kotlin suspend from Swift.** It traps in the Swift Export
     bridge (`__createProtocolWrapper(...) as! any AppResult` cast failure → frozen UI). Consume
     `AppResult` in Kotlin and expose a plain-typed `*OrNull` accessor (see `AppResult.valueOrNull` +
     the `getServerInfoOrNull`/`getResumeBookOrNull`/`downloadBookOrNull`/`ensureLocalPathOrNull`
     precedents); Swift `await`s the plain optional. Enforced by `scripts/check-no-appresult-await.sh`
     in the `Test (iOS)` CI job. (Plain-typed suspends — `String?`, domain types, `Flow`,
     fire-and-forget `Unit` — are fine.)
9. **Native, type-safe navigation.** `NavigationStack` + value-typed routes; sheets,
   `presentationDetents`, and inspectors over ported screens.
10. **Errors the iOS way.** The shared typed `AppError` crosses the boundary, but iOS maps
    it once at the native-VM boundary to alerts/inline state. Do not port the Compose
    `errorBus`/snackbar pattern.

## Design

11. **Liquid Glass + Apple HIG first — non-negotiable.** System materials only, never
    hand-rolled blur. The iOS `DesignSystem/` owns the look; do **not** port Compose design
    tokens. **iOS mockups exist at `~/Pictures/ListenUp/`** (Home, Library, Author, About
    Book, Play Audio, Settings, Statistics, auth flows). They are **pre-Liquid-Glass** — use
    them for layout/flow/content *inspiration only*; never copy their chrome or styling.
    Liquid Glass + HIG always win over the mockups. Android screens are likewise inspiration only.
12. **Responsive first, adaptive where needed — accessible always. None is polish.**
    **Make layouts RESPONSIVE, not just adaptive (Apple's WWDC-2026 direction).**
    *Adaptive* = discrete branches on size class (`if .regular { 3-col grid } else { list }`):
    two hand-tuned layouts at one breakpoint. *Responsive* = the layout flows continuously with
    the **actual available width** — column counts emerge from width, spacing/margins scale, and
    it's right at **every** point on the continuum (full-screen iPad, 1/2 + 1/3 Split View,
    Stage Manager, resizable windows), not just "iPhone vs iPad." A fixed N-column grid crushes
    cards in a narrow Split View; a width-driven one stays right everywhere.
    **Build for all screen sizes as a first-class requirement** — all iPhones, all iPads,
    landscape, Split View / Stage Manager. Prefer width-driven tools: `GridItem(.adaptive(minimum:))`
    (columns flow from width), `ViewThatFits`, `containerRelativeFrame`, width-scaled sizing.
    A genuine layout-*mode* fork (row-cards ↔ grid-cards) on `horizontalSizeClass` is still fine —
    but the chosen mode's internals must be width-responsive (its grid columns flow). Treat
    size-class branches as the fallback, not the default; cover the narrow-Split-View case (where
    an iPad reports `.compact`). Apple's newest responsive APIs aren't all in stable Xcode/iOS yet —
    approach it as far as the stable tools allow now and adopt the rest as they ship. Never ship a
    blown-up iPhone UI on iPad ("a phone layout stretched on a tablet"). iOS is more constrained
    here than Android (whose shell already adapts compact/medium/expanded), which is exactly why
    iOS needs deliberate attention. The app shell is already on `.tabViewStyle(.sidebarAdaptable)`;
    Phase 2 brings the content screens up to the responsive bar one slice at a time.
    **And accessibility *is* "follows Apple guidelines":** honor Dynamic Type, VoiceOver, and the
    Liquid Glass accessibility settings — Reduce Transparency, Increase Contrast, Reduce Motion.
    **Dynamic Type — fixed-font convention:** content text (titles, labels, captions, numerals
    the user reads) uses a semantic `.font(.title3)`/`.subheadline`/`.caption`… style or
    `@ScaledMetric`, so it scales with the user's text-size setting. A literal
    `.font(.system(size:))` is allowed **only** for a decorative glyph in a fixed-size container
    (a badge circle, an SF Symbol sized to its frame) and must carry a one-line
    `// decorative fixed size` comment. The conversion is a phased rollout, one area per slice —
    `LicensesView` is the reference precedent.

## Media & process

13. **Media platform-integration completeness.** Background audio; Now Playing Info Center
    (with artwork) + `MPRemoteCommandCenter`; lock-screen controls; AirPlay; Live Activities;
    audio-session interruption *and* route-change handling; balanced session
    activate/deactivate. **Playback persistence lifecycle:** save position on pause, on
    seek, *and* on `scenePhase`/background/termination (guarded by `beginBackgroundTask`) —
    never rely on periodic ticks alone. App Intents/Siri/interactive widgets/Control Center
    controls where they serve audiobooks. *CarPlay: evaluate later — not a current requirement.*
14. **Swift Testing (not XCTest) for native code**; shared logic keeps Kotest. Same TDD
    discipline as the root `CLAUDE.md` — no fix without a failing test first, no feature
    without a test.

## Build hygiene

15. **Stale shared-framework escape hatch.** If an iOS build links *stale* shared types after
    a Kotlin edit (the Swift Export framework didn't pick up your change — new types missing,
    old shapes still linked, no compile error), run
    `rm -rf app/sharedLogic/build/{SwiftExport,SPMPackage}` from the repo root and rebuild. The
    `*GenerateSPMPackage` task is now forced to never report `UP-TO-DATE`
    (`app/sharedLogic/build.gradle.kts`), so this should no longer be necessary — keep this note
    until that wiring is confirmed on real iOS builds, then remove it.

16. **iOS local build setup.** The iOS build drives a Gradle Swift Export embed step that
    generates and compiles the `Shared`/`ListenupContract` frameworks (Swift Export is the sole
    interop layer). Set `JAVA_HOME` to a JDK before invoking `xcodebuild`
    (CI does this in the iOS lane); any recent JDK that launches Gradle works — the Gradle
    build resolves its own Kotlin toolchain. The first build is slow because it generates and
    compiles those Swift Export frameworks; subsequent incremental builds reuse them.
    **The pre-push gate must compile the test target** — `xcodebuild build` does *not*
    (`build-for-testing` or the full `Test (iOS)` does); see `client/CLAUDE.md` "Pushing".
