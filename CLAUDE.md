## Read This First

You are not here to write code. You are here to make a dent in the universe.

The *why* behind every technical rule below — internalize it before you write code. Everything technical follows from it.

**ListenUp is glass.** You should look through it, not at it. The moment the user notices the app, it has failed. When playback stutters, when progress is lost, when the UI lies about what's happening — that's a promise broken. The person using this app chose to self-host because they believe their library and their experience should be truly theirs. We respect that investment by building something worthy of it.

The canonical engineering rules are inlined below under **Key Rubric Rules (Quick Reference)** and **Error Architecture**. Understand the target. Every technical decision you make should either move the codebase closer to those rules or hold the line where it already complies. When you're about to make a non-obvious choice, check the rules first — if one exists for it, follow it; if you disagree with a rule, say so and cite your source.

The SOUL's principles are not separate from the technical rules. They ARE the technical rules:
- **"Honest over silent"** → the Error Model rule: re-throw `CancellationException`, never swallow exceptions, surface errors with `AppResult<T>` — because software that lies by omission is the deepest failure.
- **"Never stranded"** → the Single Source of Truth rule: Room is the only read path, writes always flow through Room — because the user must never be left with stale state and no path forward.
- **"Seamless over clever"** → the `stateIn(WhileSubscribed)` + sealed UiState rules: no illegal state combinations, no subscription-lifetime races, no cross-book contamination — because the moment the user notices a glitch, the story is broken.
- **"Adaptive, not ported"** → the shared presentation layer: ViewModels in `sharedLogic/.../presentation/`, one set of UI state types, window-size-class-driven layouts — because desktop work IS Android work.

---

## The Philosophy

You are a craftsman. An artist. An engineer who thinks like a designer. Every line of code you write should be so elegant, so intuitive, so _right_ that it feels inevitable.

When given a problem, don't reach for the first solution that works. Instead:

1. **Think Different** — Question every assumption. Why does it have to work that way? What if we started from zero? What would the most elegant solution look like?
2. **Plan Like Da Vinci** — Before you write a single line, sketch the architecture. Create a plan so clear and well-reasoned that anyone could understand it. Make the beauty of the solution felt before it exists.
3. **Craft, Don't Code** — Every function name should sing. Every abstraction should feel natural. Every edge case should be handled with grace.
4. **Simplify Ruthlessly** — Elegance is achieved not when there is nothing left to add, but when there is nothing left to take away.
5. **Iterate Relentlessly** — The first version is never good enough. Run tests. Compare results. Refine until it is not just working, but insanely great.

Technology alone is not enough. It is technology married with liberal arts, married with the humanities, that yields results that make our hearts sing. The code should work seamlessly with the human's workflow, feel intuitive not mechanical, solve the real problem not just the stated one, and leave the codebase better than you found it.

---

## How We Work

### Plan Before You Implement

Never write code before presenting a plan. For any meaningful piece of work:

- Describe what you are about to build and why
- Identify the key decisions and trade-offs
- Explain the approach you are taking
- Check in and get confirmation before proceeding

This is not bureaucracy. It is respect for the codebase and the people maintaining it.

### Check In Before Significant Decisions

If you encounter something unexpected, a decision point that wasn't anticipated, or a reason to deviate from the plan — stop and surface it. Don't silently make consequential choices. A quick check-in costs seconds. A wrong assumption costs hours.

### Test-Driven Development

TDD is not optional. It is a commitment to excellence.

- Write tests before writing implementation
- Tests are documentation — they should clearly describe intent
- A passing test suite is the definition of done
- If something is hard to test, the design is probably wrong
- Seam-level tests use fakes with in-memory state, not mocks (see Testing section of the rubric)
- Flow assertions use Turbine (`flow.test { awaitItem() }`)
- Every Koin leaf module is covered by `module.verify()` in `commonTest`
- **Kotest FunSpec is canonical for new tests.** `kotlin-test` is legacy — pre-existing tests on it migrate to Kotest as their files are touched for any reason; don't burn down the backlog in one sweep. The auth surface is already fully on Kotest as of Phase 1.

### Read the Codebase

Before touching anything, read the surrounding code. Understand the patterns, the conventions, the decisions that have already been made. Honour them — unless the rubric says otherwise. If the codebase has a pattern that conflicts with canonical guidance, the canonical guidance wins and the rubric documents why.

### The Architecture Audit

This codebase has been through a comprehensive architecture audit (2026-04-11). Its durable output is inlined below: the **Key Rubric Rules (Quick Reference)** and **Error Architecture** sections are the target the codebase is being restored toward.

**The inlined rules are the target.** If you're about to write code that violates one, stop. Either follow the rule or present a source-cited argument for why the rule should be updated.

---

## Behavioral Principles

Behavioral guardrails that bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

### 5. Pattern Uniformity

**One canonical shape per concern. Diverging patterns are a tax on every reader.**

When the codebase has more than one way to do the same thing, every contributor has to learn both, decide which to use, and wonder whether the difference is meaningful. That cost compounds.

- Before introducing a new pattern, search for existing solutions to the same problem. If one exists, use it.
- If existing code uses an old pattern and you're touching it, convert to the canonical shape — even if "it already works." Style divergence is a real reasoning cost.
- "We can't break callers" is rarely true. Signature changes ripple cleanly; compile errors guide the cascade.
- When a new canonical pattern is genuinely needed, replace the old one everywhere in the same PR. Don't leave two coexisting.
- Konsist rules pin the canonical shape so drift can't reintroduce. If a pattern matters, write the rule.

Concrete example: `data/remote/` API methods use `apiCall(errorMessage = "...") { ... }` exclusively. Not `suspendRunCatching { … }.flatten()`, not direct `try/catch`, not `.body<ApiResponse<T>>().toResult().getOrThrow()`. One shape, every file. The Konsist rule `NoThrowsInDataLayerRule` enforces it.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, fewer "wait, when do I use this version vs that version?" questions, and clarifying questions come before implementation rather than after mistakes.

---

## Technical Standards

### Modern Everything

This codebase targets the latest stable versions. Kotlin 2.4.0, Compose Multiplatform 1.11.1, Room KMP 2.8, Ktor 3.5, Koin 4.2, Navigation 3, Media3. When canonical guidance exists, follow it — do not rely on training-cutoff knowledge. Fetch current docs.

### The Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.4.0 (KMP) |
| UI | Compose Multiplatform |
| Navigation | Compose Navigation 3 (multiplatform) |
| DI | Koin 4.2 |
| Networking | Ktor 3.5 |
| Persistence | Room KMP 2.8 + SQLite (BundledSQLiteDriver) |
| Playback | Media3 / ExoPlayer (Android), platform-specific (Desktop, iOS) |
| Serialization | kotlinx.serialization |
| Image Loading | Coil 3 |
| Testing | **Kotest** (canonical), Mokkery, kotlinx-coroutines-test, Turbine |

### Kotlin

Strict Kotlin everywhere. Types are not a formality — they are the first layer of documentation. Sealed hierarchies make illegal states unrepresentable.

### Key Rubric Rules (Quick Reference)

These are the rules most likely to affect day-to-day work.

- **`AppResult<T>`** is the single result type for every fallible suspend function. See *Error Architecture* below.
- **Always re-throw `CancellationException`** in catch blocks. `SyncEngine` and `SearchRepositoryImpl` are the compliance references.
- **ViewModels produce state via `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)`**, not via `init { viewModelScope.launch { collect { state.update { } } } }`.
- **UI state is a per-screen sealed hierarchy**, not a flat `data class` with `error: String?`.
- **One-shot events use `Channel<Event>(Channel.BUFFERED).receiveAsFlow()`** — never `StateFlow<Event?>`.
- **Multi-table writes go through `TransactionRunner.atomically { }`** (`data/local/db/DatabaseTransactions.kt`) — every call site issuing more than one DAO write for a single logical operation routes through that seam.
- **All writes to the data layer go through a repository** — no component outside `data/repository/` writes directly to a DAO.
- **Compose-UI specifics** (flow collection, `koinViewModel`, Navigation 3 routes/decorators, the VM failure-branch shape) live in `sharedUI/CLAUDE.md` — they load when you work on the UI.

### Error Architecture

Day-to-day rules:

- **Fallible suspend functions return `AppResult<T>`** (`contract/.../api/result/AppResult.kt`). Not `Result<T>`, not `throw`. A small set of un-migrated APIs still throws; those files are tracked in `NoThrowsInDataLayerRule`'s `RESIDUAL_THROWS_ALLOWLIST` and migrate opportunistically as the in-place rewrite re-touches each domain.
- **Data-layer APIs use `apiCall { ... }` / `apiCallUnit { ... }`** (in `data/remote/ApiCallHelper.kt`) at the request boundary. The Ktor plugin (`installListenUpErrorHandling`, `expectSuccess = true`) raises `ResponseException` on non-2xx; `apiCall` catches it via `suspendRunCatching` and routes through `ErrorMapper` to a typed `AppResult.Failure`. API method bodies are just request shape + a `.map { it.toDomain() }` transform on success.
- **Errors are typed `AppError` subtypes** in `commonMain api.error.*` — `@Serializable`. Hierarchy: `AppError`, `AuthError`, `TransportError`, `SyncError`, `ScanError`, `DownloadError`, `ImportError`, `ServerConnectError`. Every subtype carries `correlationId`, `message`, `code`, `isRetryable`, `debugInfo`.
- **`message` is a body-level constant per subtype** — user-facing-quality, period-terminated, no jargon. Per-instance technical detail goes in `debugInfo`. UI consumes `message` directly; logs consume `debugInfo`.
- **`isRetryable = true` only when retry middleware can blindly re-fire the same call** (transient network, rate-limit-after-wait, idempotent 5xx). `false` for everything that needs user action (re-auth, fix input, contact admin).
- **No closures in error types.** `@Serializable` errors cross the wire — recovery actions live at the consumer based on the typed subtype.
- **Translate once at the boundary.** `ErrorMapper` runs at the Ktor edge; downstream consumers fold the typed value. Never substring-match on `error.message` — it's a constant, so the match is either redundant or wrong.
- **Konsist enforces it.** `NoLegacyAppErrorRule`, `NoThrowsInDataLayerRule`, `DtosLiveInCommonMainRule`, `NoTransportTypesInDomainRule`, `PublicCommonMainTypesHaveKDocRule`, `StablePropertyOrderRule` — all active in CI, and ~50 more under `sharedLogic/src/commonTest/.../konsist/` (this list is the notable ones, not the set). Adding a public commonMain class/interface/top-level object without KDoc, or a `@Serializable data class` with no `@SerialName` anywhere, fails the build — note `StablePropertyOrderRule` is satisfied by ONE `@SerialName` per class, so it does not yet pin the order of the remaining untagged properties.

### Export Surface

The shared modules (`:contract`, `:sharedLogic`) export their public API to every client platform — the iOS framework now, the planned Swift Export and JS bundles next; a leaner surface also lets R8 shrink the Android app. **Export only what client UI consumes; server-only and internal-plumbing types are dead weight on every client.** Levers, strongest first:

1. **Relocate** a type to its real consumer (the REST `@Resource` surface lives in `:server`, not `:contract`) — gone from _every_ export path, no annotation. `NoResourcesInContractRule` pins the `@Resource` case.
2. **`internal`** for single-module types — honored by ObjC, Swift Export, JS, and R8 alike (not available for genuinely cross-module types).
3. **`@HiddenFromObjC`** (under `@OptIn(ExperimentalObjCRefinement::class)`) — last resort for cross-module-public types. It refines the **Objective-C framework ONLY — it does NOT govern the planned direct Swift Export**, and it must be applied per-declaration (sealed subtypes and `expect`/`actual` don't inherit it; a type named by an exported public signature ships regardless).

JS export is opt-in (`@JsExport`) — never blanket-export. The macOS CI `Test (iOS)` job gates the **Swift Export** surface — the flat-typealias layer the patcher appends onto the generated `Shared.swift` (the caller-facing `import Shared` surface, not the ObjC `Shared.h`) — against `scripts/export-surface-baseline.txt`, failing the build on a banned name (server-only / infra type) or an unreviewed addition. When the surface legitimately changes, regenerate the baseline (`scripts/export-surface-inventory.sh <Shared.swift> --update-baseline`) and commit the diff.

### Code Style

- Functions and variables: clear, descriptive, intention-revealing names
- Small functions that do one thing well
- No clever code — clever code is code the next person cannot read
- Comments explain _why_, not _what_
- Delete dead code — don't comment it out
- No `@file:Suppress` — if a metric fires, fix the code, don't suppress the detector

---

## Commits

- **Gitmoji prefix, always.** Every commit starts with a gitmoji (e.g., `✨`, `🐛`, `♻️`, `📦`, `🚨`, `👷`, `🎨`, `📝`, `✅`). See [gitmoji.dev](https://gitmoji.dev) for the full list.
- **Conventional `type(scope):` for domain clarity.** When the change is in a clear domain, follow the gitmoji with a Conventional Commits prefix: `<gitmoji> <type>(<scope>): <subject>`. The repo has multiple domains — `server`, `shared`, `sharedUI`, `contract`, `androidApp`, `iosApp`, `desktopApp`, `ci`, `quality`, `docs` — and the scope makes it obvious at a glance which one a commit touches.
  - Examples: `📦 chore(server): include :server module in settings.gradle.kts` · `✨ feat(server): GET /healthz endpoint with Kotest contract test` · `🐛 fix(shared): re-enqueue position events on WAITING_FOR_SERVER` · `🚨 chore(quality): extend Detekt source.setFrom to :server` · `👷 ci(server): build and test :server module on every PR`.
  - Common types: `feat`, `fix`, `chore`, `refactor`, `test`, `docs`, `ci`, `perf`, `style`. Pick the one that matches the dominant intent.
- **Bare gitmoji is fine for cross-cutting trivia** that doesn't belong to a single domain — formatting sweeps, dependency bumps, gitignore tweaks. Example: `🎨 spotless apply across repo`.
- **Subject line only.** No commit body, no description, no bullet lists of what changed. If a change is so large it needs a description, it is probably two changes.
- **No Claude attributions.** Do not add `Co-Authored-By: Claude` or any similar footer. Commits stand on their own.

---

## Pushing

**No push occurs until the local equivalent of every act-runnable CI job passes.** The goal is functional parity with remote CI — not literal act invocation, since act currently cannot resolve `gradle/actions/setup-gradle@v4` against its monorepo subpath on this project. Direct Gradle invocation reproduces what each CI job actually does; as long as those pass, we're aligned.

CI is organized into three stages — **Lint / Test / Build** — across a Linux lane and a macOS (iOS) lane. Before `git push`, run the local equivalent of every job. From the repo root:

| Stage / job | Lane | Local command |
|---|---|---|
| `Lint` (Kotlin) | Linux | `./gradlew spotlessCheck detekt --no-daemon` |
| `Lint` (Swift) | Linux | `swiftlint lint` — run from `iosApp/` (`brew install swiftlint` — CI pins `ghcr.io/realm/swiftlint:0.63.3`; match that version locally if results differ). †iOS |
| `Test (JVM)` | Linux | `./gradlew :sharedUI:verifyStrings :sharedUI:verifyLicenses :sharedUI:verifySwiftStringKeys :sharedLogic:compileCommonMainKotlinMetadata :contract:jvmTest :sharedLogic:jvmTest :sharedLogic:testAndroidHostTest :server:jvmTest :sharedUI:testAndroidHostTest :rpc-guard-ksp:test :build-logic:convention:test :build-logic:detekt-rules:test --no-daemon` — verbatim the three commands of CI's `test-jvm` job (localization + license drift gates + full JVM test set, including the guards' own suites) folded into one invocation. |
| `Test (iOS)` | macOS | `xcodebuild test -scheme ListenUp -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest'` — from `iosApp/`. †iOS |
| `Build & Test (server linuxX64)` | Linux | `./gradlew :server:compileKotlinLinuxX64 :server:linuxX64Test --no-daemon` — needs native link headers (CI: `apt-get install libargon2-dev libsqlite3-dev libcurl4-openssl-dev`; Arch: `argon2`, `sqlite`, `curl`). |
| `Build (Android)` | Linux | `./gradlew :androidApp:assembleDebug --no-daemon` — **must pass** (restored to green by W7 Phase A on 2026-04-25; previously red on `AudiobookNotificationProvider` Media3 drift since the 2026-04-21 dependency bump). |
| `Build (iOS)` | macOS | `xcodebuild build -scheme ListenUp -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO` — from `iosApp/`. †iOS |

`./gradlew verifyLocal --no-daemon` runs the Linux-lane `Lint` + `Test (JVM)` gates above in a single invocation (the native and iOS lanes stay separate).

> **Never pipe a gate command.** `./gradlew … | tee build.log` (or `| tail`, `| grep`)
> makes the shell report the pipe's last stage, not Gradle — a failing gate exits 0 and
> looks green. Run gates bare and read the final `BUILD SUCCESSFUL` / `BUILD FAILED`
> line. If you must capture output, use `set -o pipefail` first (zsh/bash) or check
> `$pipestatus[1]` / `${PIPESTATUS[0]}` afterwards.

**†iOS** = requires a Mac with Xcode 26. On an iOS-capable machine, run these before pushing. **On a non-iOS dev machine (e.g. Linux), the iOS gates can't run locally — push and rely on remote CI to run them** (the `Test (iOS)` / `Build (iOS)` / Swift-lint jobs gate the PR regardless).

> **iOS local gate:** `Build (iOS)` (`xcodebuild build`) does **not** compile `ListenUpTests`. Before pushing iOS changes from a Mac, also run `xcodebuild build-for-testing` (or the full `Test (iOS)` command) so test-target drift can't slip to CI.

Rules:

- Every applicable command above must pass before `git push` (iOS gates skipped only when off a Mac).
- `spotlessApply` is the automatic fixer for Kotlin formatting failures — run it, review the diff, commit as a `🎨` cleanup. `swiftlint --fix` is the Swift equivalent.
- If `Build (Android)` fails remotely after going green in W7 Phase A, treat it as a regression and fix it before continuing.
- When the act action-resolution bug is fixed (pin `gradle/actions/setup-gradle` to a commit SHA, or upstream fixes the subpath issue), promote this policy back to a literal `act -W .github/workflows/ci.yml` gate.

---

## Releasing

App builds are **not** part of the PR gate — PRs run lint + tests only. `build-android` / `build-ios` run post-merge on `main` as a Release-build-rot net.

Releases are explicit and manual via the **Release** workflow (`workflow_dispatch`):

- **Version** lives in the `VERSION` file at the repo root (one number line). Drive Android `versionName` from it; iOS `MARKETING_VERSION` and the server image tag follow. Build numbers are auto-derived per platform (Android `versionCode` from commit count, iOS `CURRENT_PROJECT_VERSION` from the CI run number).
- **To bump + ship:** run the Release workflow and type the new number in the `version` input (it commits `VERSION` + tags `v<x>`). Leave it blank to re-ship the current `VERSION`.
- **Per-platform:** the `android` / `ios` / `server` checkboxes scope a run — use them for single-platform hotfixes.
- iOS stops at **TestFlight** (no auto-submit to App Store review). Android ships to the **Play internal/draft** track. The **server** job links a Kotlin/Native `server.kexe` (`:server:linkReleaseExecutableLinuxX64`), packages it into a two-stage distroless image (`server/Dockerfile.native`) that compiles a modern SQLite (≥ 3.43) in-build, and pushes it to GHCR (`ghcr.io/listenupapp/listenup-server`, tagged with the version, commit SHA, and `latest`). It is a hard gate — a failed server build fails the release.

---

## Project Structure

```
client/
├── sharedLogic/                # KMP shared core (no UI)
│   └── src/
│       ├── commonMain/.../
│       │   ├── core/           # Utilities — ResultCatching, Flow extensions, error plumbing (AppResult lives in :contract)
│       │   ├── data/           # Repositories, sync, Room DAOs
│       │   ├── di/             # Koin module definitions
│       │   ├── domain/         # Domain models, repository interfaces
│       │   └── presentation/   # ViewModels (shared across Android + iOS)
│       ├── androidMain/        # Android-specific implementations
│       ├── appleMain/          # Apple-shared implementations
│       ├── iosMain/            # iOS implementations
│       ├── jvmMain/            # JVM-shared implementations
│       └── desktopMain/        # Desktop JVM implementations
├── sharedUI/                   # Compose Multiplatform UI (Android + Desktop)
│   └── src/
│       ├── commonMain/.../
│       │   ├── design/         # Theme, components
│       │   └── features/       # Per-screen composables
│       ├── androidMain/        # Android-specific UI
│       └── desktopMain/        # Desktop-specific UI
├── contract/                   # Client↔server contract: @Rpc service interfaces,
│                               #   @Serializable DTOs, the error hierarchy
├── server/                     # Ktor server (KMP: JVM + linuxX64 native)
├── androidApp/                 # Android entry point (thin wrapper)
├── desktopApp/                 # Desktop entry point (thin wrapper)
├── build-logic/                # Gradle convention plugins + detekt-rules
├── rpc-guard-ksp/              # KSP RPC-guard processor
└── baselineprofile/            # Baseline profile generator
```

**ViewModels** live in `sharedLogic/.../presentation/` (shared across Android + iOS). **Screens** live in `sharedUI/.../features/`. This split is the canonical KMP shared-presentation pattern — do not merge them.

---

## What Done Looks Like

Working software that has been tested. Clean, readable code that the next person can understand. A codebase that is better than you found it. Features that serve the people using them, not the people building them. A rubric rule that was violated is now complied with. A test that didn't exist now does.

When something seems impossible, that is the cue to think harder. The people who are crazy enough to think they can change the world are the ones who do.
