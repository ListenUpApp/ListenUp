# CLAUDE.md — Compose UI (`sharedUI`)

Design & platform rules for the Compose Multiplatform UI (Android-primary; also Desktop).
These **complement** the root `client/CLAUDE.md` and the `android-*` skills — they do **not**
restate architecture. For ViewModels/MVI/state/error/Compose mechanics, see the root + the
`android-compose-ui` / `android-presentation-mvi` / `android-navigation` skills.

> **Mental model:** all the Compose UI lives here. Build to our design system and reuse;
> adapt to every screen size; lean fully into Material 3 Expressive.

## Design language
1. **Material 3 Expressive is the design language.** The app is wrapped in `MaterialExpressiveTheme`
   + `MotionScheme.expressive()` (`design/theme/Theme.kt`). Use the real expressive APIs — emphasized
   type roles, `ButtonGroup`/`ToggleButton`/`SplitButtonLayout`/`FloatingActionButtonMenu`, the
   shape-morphing `LoadingIndicator`, `MaterialShapes` morphing — **not** hand-rolled approximations.
   Lean bold: emphasized type for hero/title/header text; expressive motion for state changes.
2. **Build & reuse UI primitives — Pattern Uniformity applied to UI.** `design/` owns canonical
   components built to our style (one Avatar, one TextField with variants, one Card, one Chip, one
   cover-scrim). Feature screens **compose** them — never re-roll. When you find duplication
   (multiple text-field/avatar impls, copy-pasted material-capsule/scrim recipes), consolidate it.
3. **Adaptive across all screen sizes — first-class, not "a phone layout stretched on a tablet."**
   Every screen earns a real layout at compact / medium / expanded widths via `WindowSizeClass`,
   `NavigationSuiteScaffold` / `WideNavigationRail`, and list-detail. The shell already adapts
   (compact/medium/expanded) — hold that bar on new screens. (Mirrors the iOS adaptive rule.)

## Platform & language
4. **Android 14/15 platform modernity.** Adopt predictive back, per-app language, the Photo Picker,
   and granular media permissions where they serve the app.
5. **Modern Kotlin** — Kotlin 2.3.20 (catalog-pinned). Idiomatic coroutines/Flow per the root rubric.

## Multiplatform
6. **`commonMain` is Android + Desktop.** The Expressive APIs are in multiplatform material3, and
   `androidx.graphics:graphics-shapes` has a `-desktop` variant — so expressive work (incl. shape
   morphing) stays `commonMain`. Only platform-specific bits (e.g. dynamic color) live in
   `androidMain`/`desktopMain` actuals. (Desktop is a future deploy target, not the current priority;
   keep `commonMain` Desktop-valid so we don't add to its drift.)

## Compose-UI conventions (moved from root — these are Compose-UI-specific)
7. **Collect flows with `collectAsStateWithLifecycle()`**, not `collectAsState()`.
8. **ViewModels are declared with `viewModelOf(::Ctor)`** and retrieved in composables with `koinViewModel()`.
9. **Navigation routes implement `NavKey`**; back stacks use `rememberNavBackStack`; `NavDisplay` installs
   entry decorators for per-entry VM scoping.
10. **VM failure-branch shape** (the Compose error-presentation pattern — iOS maps errors its own way per
    `iosApp/CLAUDE.md` rule 10): on `AppResult.Failure`, `errorBus.emit(result.error)` for the global
    snackbar and set the screen's `State.Error(userMessageFor(result.error))`:
    ```kotlin
    when (val result = repo.foo()) {
        is AppResult.Success -> _state.value = State.Loaded(result.data)
        is AppResult.Failure -> {
            errorBus.emit(result.error)
            _state.value = State.Error(userMessageFor(result.error))
        }
    }
    ```
