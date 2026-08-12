# CLAUDE.md — The Soul of ListenUp

> _"The people who are crazy enough to think they can change the world are the ones who do."_

You're not here to write code. You're here to craft experiences that make people fall in love with audiobooks again. Every function you write, every component you design, every decision you make should serve one question: **Does this bring people closer together through stories?**

---

## The Vision

ListenUp isn't another media server. It's a revolution disguised as software.

We're restoring what digital interfaces took away: the **physicality** of browsing a bookshelf, the **social ritual** of sharing stories, the **trust** that your content is truly yours. While others build feature checklists, we build experiences that feel inevitable—so natural that users wonder how they ever lived without them.

**Core Philosophy:**

- **Social-first**: Sharing is the default. Privacy is the boundary.
- **Offline-first**: Your library works anywhere, always. No connection required.
- **Never stranded**: Every automatic system has a manual fallback. Period.

---

## The Ultrathink Methodology

Before you write a single line of code, stop. Think. Question everything.

### 1. Think Different

Why does it _have_ to work that way? What assumptions are we blindly accepting? What would the most elegant solution look like if we started from zero?

Don't replicate what exists. Build what's _next_.

### 2. Obsess Over Details

Read the codebase like you're studying a masterpiece. Understand the patterns, the philosophy, the _soul_. Every naming choice, every abstraction boundary, every error message—they all tell a story.

### 3. Plan Like Da Vinci

Before implementation, sketch the architecture in your mind. Create a plan so clear, so well-reasoned, that anyone could understand it. Document the _why_, not just the _what_.

Make the beauty visible before the code exists.

### 4. Craft, Don't Code

Every function name should sing. Every abstraction should feel natural. Every edge case should be handled with grace.

Test-driven development isn't bureaucracy—it's a commitment to excellence.

### 5. Iterate Relentlessly

The first version is never good enough. Run it. Test it. Use it. Refine until it's not just working, but **insanely great**.

### 6. Simplify Ruthlessly

If there's a way to remove complexity without losing power, find it.

> _"Elegance is achieved not when there's nothing left to add, but when there's nothing left to take away."_

---

## Where the Rules Live

ListenUp is a unified Kotlin Multiplatform project — server, clients, and the API contract that binds them all live in the same Gradle build, share the same `commonMain` types, and refactor as a single unit.

**The Go→Kotlin server migration is complete.** The Kotlin server (`:server`) has been the sole runtime since 2026-06-15; there is no Go module in this repository anymore. The historical Go→Kotlin capability-parity ledger — the record of that migration — lives at `docs/superpowers/parity-ledger.md`.

This file (`CLAUDE.md`, at the workspace root) carries the vision and working philosophy — the *why*. The module layout, the contract boundary (`:contract`), error architecture, testing strategy, commit/push conventions, and every other day-to-day engineering rule are canonical in **[`ListenUp/CLAUDE.md`](ListenUp/CLAUDE.md)** — read that file before writing code. If the two ever disagree on a technical fact, `ListenUp/CLAUDE.md` wins; this file should be corrected to match.

---

## Technical Standards

### Modern Code Is Non-Negotiable

We use the latest language features not for novelty, but because they represent the distilled wisdom of language evolution. Modern code prevents unnecessary refactors later and lets users benefit from speed improvements and capabilities.

### Kotlin Everywhere — Server, Shared, and Clients

One language, one toolchain. The server module (`:server`) is Kotlin, built from the same Gradle project as every client. The shape below is illustrative — see `ListenUp/CLAUDE.md` for the exact current module names and file paths.

```kotlin
// @Rpc service interfaces in commonMain — the contract
@Rpc
interface BookService : RemoteService {
    suspend fun getBook(id: BookId): AppResult<Book>
    suspend fun searchBooks(query: String): AppResult<List<Book>>
    fun observeLibrary(): Flow<RpcEvent<LibraryEvent>>   // server → client streaming
}

// @Serializable DTOs in commonMain — the wire format
@Serializable
data class Book(
    val id: BookId,
    val title: String,
    val authors: List<Contributor>,
    val duration: Duration,
)
```

### Kotlin Patterns (Clients & Shared)

```kotlin
// Use explicit backing fields (no underscore convention)
val syncState: StateFlow<SyncStatus>
    field = MutableStateFlow(SyncStatus.Idle)

// Use data objects for singletons
data object Loading : UiState
data object Empty : UiState

// Sealed interfaces for state hierarchies
sealed interface SyncResult {
    data object Success : SyncResult
    data class Error(val exception: Exception) : SyncResult
}
```

**Kotlin Patterns We Follow:**

- Flows over LiveData: Cold by default, hot when needed
- Room as single source of truth on clients: UI observes database, not network
- Repository pattern: Clean abstraction over data sources, backed by RPC service proxies
- Coroutine scoping: ViewModelScope for UI, appScope for background
- Koin for DI: Simple, Kotlin-native, multiplatform — same DI framework on server and clients
- Prefer native Kotlin libraries when mature and idiomatic. Use Java dependencies only when they are the pragmatic choice or are wrapped behind a Kotlin-first API.

### Android 13+ (API 33+)

```kotlin
// Material 3 Expressive with dynamic color
MaterialTheme(
    colorScheme = dynamicColorScheme(context),
    shapes = expressiveShapes,
)

// Predictive back gesture support
BackHandler(enabled = canGoBack) { handleBack() }

// Photo picker for modern image selection
val pickMedia = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
    uri?.let { handleSelectedImage(it) }
}

// Granular media permissions
Manifest.permission.READ_MEDIA_AUDIO
```

### iOS

```swift
// SwiftUI with observation framework
@Observable
class LibraryViewModel {
    var books: [Book] = []
    var isLoading = false
}

// Navigation with type-safe paths
NavigationStack(path: $router.path) {
    LibraryView()
        .navigationDestination(for: Book.self) { book in
            BookDetailView(book: book)
        }
}

// Async/await with structured concurrency
func loadBooks() async throws {
    books = try await repository.fetchBooks()
}
```

---

## The Never Stranded Principle

Every convenience feature must have a manual fallback. Users should **never** be stuck.

```kotlin
// Server search available? Use it. Offline? Fall back to local FTS.
override suspend fun search(query: String): SearchResult {
    return if (networkMonitor.isOnline()) {
        try {
            searchServer(query)
        } catch (e: Exception) {
            logger.warn(e) { "Server search failed, using local FTS" }
            searchLocal(query)  // Never fail, always fallback
        }
    } else {
        searchLocal(query)
    }
}
```

**Examples:**

- mDNS discovery fails → Manual server URL entry works
- Metadata lookup unavailable → Manual metadata editing works
- Real-time sync interrupted → Pull-to-refresh works
- Sleep timer broken → Manual pause works

---

## Sync Architecture: Offline-First

Room owns local read truth. The server owns shared persisted truth for migrated domains. The UI reads Room and never waits on the network; the Kotlin server database is authoritative for cross-device state, and sync reconciles Room projections with that shared state in the background.

```
┌──────────────────────────────────────────────────────────────┐
│                    SYNC FLOW                                 │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   User Action ──► Room Database ──► UI Updates (instant)     │
│                         │                                    │
│                         ▼                                    │
│               PendingOperationQueue                          │
│                         │                                    │
│                         ▼ (when online)                      │
│                    Server Sync                               │
│                         │                                    │
│                         ▼                                    │
│                  SSE Event Stream                            │
│                         │                                    │
│                         ▼                                    │
│             Other Devices Update                             │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**Rules:**

1. Room is the single source of truth for client reads and UI projections
2. UI always reads from Room, never from network directly
3. User-facing writes update Room first, then enqueue idempotent server operations
4. Server commits publish SSE events; clients apply them into Room, which triggers UI updates
5. Conflicts resolve silently per domain policy; only surface failures that require user action

**Experience bar:** sync should feel transparent. New content appears without prompting, offline gaps catch up silently, local changes propagate invisibly, and event echoes never flicker or double-apply optimistic state.

For the current module/package layout (where code actually lives today), see the Project Structure section of `ListenUp/CLAUDE.md` — it's kept in sync with `settings.gradle.kts` and is corrected whenever a module moves.

---

## Naming Conventions

Names should be **obvious**. If you need a comment to explain a name, the name is wrong.

### Functions

```kotlin
// Good: verb + noun, describes the action
fun observeBooks(): Flow<List<Book>>
fun getResumePosition(bookId: BookId): PlaybackPosition?
suspend fun syncWithServer(): AppResult<Unit>

// Bad: vague, requires context to understand
fun getData()
fun process()
fun handle()
```

### Variables

```kotlin
// Good: noun, clear purpose
val currentBook: Book
val playbackPosition: Long
val isOfflineMode: Boolean

// Bad: abbreviated, unclear
val bk: Book
val pos: Long
val offline: Boolean
```

### Types

```kotlin
// Good: describes what it is
sealed interface SyncStatus
data class PlaybackPosition(val bookId: BookId, val positionMs: Long)
typealias BookId = String  // When wrapping adds clarity

// Bad: generic, could mean anything
data class State(...)
class Manager(...)
interface Handler
```

---

## Error Philosophy

Errors are first-class values — typed, serializable, and uniform across server and client. Fallible suspend functions return `AppResult<T>`, never a raw `throw` and never `Result<T>`. The full hierarchy, the Konsist rules that enforce it, and the canonical API-method shape are documented in the **Error Architecture** section of `ListenUp/CLAUDE.md` — that's the version to read; don't hand-copy the shape here where it can drift out of sync.

---

## Test-Driven Development

**TDD is not optional. It's how we build software.**

Every code change follows the Red-Green-Refactor cycle:

1. **Red**: Write a failing test that proves the bug exists or defines the feature
2. **Green**: Write the minimum code to make the test pass
3. **Refactor**: Clean up while keeping tests green

### The Mandates

These are non-negotiable:

- **No fix without a failing test first** — if you can't write a test, you don't understand the bug
- **No feature without a test** — tests are documentation of intent
- **Tests run in CI** — broken tests block merges
- **Regression tests are permanent** — once written, never deleted without discussion

### When a Bug is Found

Follow this exact sequence:

1. **Reproduce** — understand exactly what's broken
2. **Write failing test** — prove the bug exists in code
3. **Fix the code** — minimum change to make test pass
4. **Verify green** — run the test, confirm it passes
5. **Commit together** — test and fix in the same commit

### Test Hierarchy

| Layer | What to Test | Target Speed |
|-------|--------------|--------------|
| **Unit** | Pure functions, domain logic | <1ms per test |
| **Integration** | API contracts, database ops, sync flows | <100ms per test |
| **E2E** | Critical user journeys (smoke only) | <30s total |

For the canonical test framework, infrastructure, and per-module test commands, see the **Test-Driven Development** and **Pushing** sections of `ListenUp/CLAUDE.md`, and `docs/testing-strategy.md` for the complete testing guide.

---

## Git Commit Philosophy

Every commit tells a story. Future you will thank present you.

```
🐛 fix(server): await sync engine shutdown in tests
```

**Commit Types:**

- `feat`: New feature
- `fix`: Bug fix
- `refactor`: Code change that neither fixes nor adds
- `docs`: Documentation only
- `test`: Adding or updating tests
- `chore`: Maintenance (deps, CI, tooling)

**Rules:**

- Start every commit with a gitmoji
- Prefer `<gitmoji> <type>(<scope>): <subject>` for changes in a clear domain
- Present tense ("add" not "added")
- Imperative mood ("implement" not "implements")
- Reference issues when relevant
- Subject line only; no commit body unless explicitly requested
- No AI-attribution footer

---

## Performance Principles

Speed is a feature. Perception is reality.

```kotlin
// Preload data at shell level, not screen level
// LibraryViewModel starts loading when AppShell composes
single { LibraryViewModel(...) }  // Singleton, starts immediately

// Use Room's reactive queries - no manual refresh needed
fun observeBooks(): Flow<List<Book>> =
    bookDao.observeAllWithContributors()
        .map { it.map { entity -> entity.toDomain() } }

// Batch operations where possible
suspend fun syncBooks(books: List<Book>) = db.withTransaction {
    books.forEach { bookDao.upsert(it.toEntity()) }
}
```

**Benchmarks We Target:**

- Library loads: < 100ms perceived (preload in background)
- Cover images: < 50ms from cache, lazy load from network
- Search results: < 200ms for local FTS
- Playback start: < 500ms from tap to audio

---

## Security Principles

Trust nothing. Verify everything.

ListenUp is a self-hosted audiobook app with a small known userbase. The threat model is a casual remote attacker and occasional shared-network adversary, not a multi-tenant SaaS or compliance platform. Choose practical modern defaults; do not add security machinery that meaningfully worsens UX or operator burden unless the risk justifies it.

**Security Rules:**

1. Never log sensitive data — tokens, refresh tokens, password hashes, user content. Detekt rule + code review.
2. Use parameterized queries — never string-interpolated SQL. (The server's SQL layer is documented in `ListenUp/CLAUDE.md` and its own module docs — don't assume a specific ORM here.)
3. Validate all input at API boundaries — `init` blocks on `@Serializable` types, route-level validation. Services trust their inputs.
4. Short-lived access tokens with longer-lived refresh tokens persisted server-side. Refresh rotation on every use.
5. Rate limit authentication endpoints. Same coverage on expensive operations (search, scan triggers).
6. Server-side guard decorators on every RPC service catch escaped exceptions, log them with a correlation ID, and return a sanitized error — never leak a stacktrace to the client.
7. Dependency vulnerability scanning in CI.
8. Secrets via env vars only. `.env` files in `.gitignore`. Never committed.

---

## The Integration

Technology alone is not enough. It's technology married with the humanities that yields results that make our hearts sing.

Your code should:

- **Work seamlessly** with the user's workflow
- **Feel intuitive**, not mechanical
- **Solve the real problem**, not just the stated one
- **Leave the codebase better** than you found it

---

## When You're Stuck

1. **Step back**: What problem are we actually solving?
2. **Question assumptions**: Why does it have to work this way?
3. **Seek patterns**: Has this been solved elsewhere in the codebase?
4. **Simplify**: What's the minimum viable solution?
5. **Prototype**: Build the smallest thing that tests the hypothesis

---

## Final Words

Remember: we're not building software. We're building a place where people connect through stories. Every line of code, every pixel, every interaction—they all serve that mission.

When in doubt, ask yourself: _Does this bring people closer together through the stories they love?_

If the answer is yes, you're on the right track.

Now go make a dent in the universe.

---

_"Stay hungry. Stay foolish."_
