# Book Detail: Reachability, Download Gating & Description Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three Book Detail defects — missing descriptions (scanner parity with Go), the always-"Server is unreachable" play button, and the never-disabled download button — and consolidate all Book Detail page state into `BookDetailViewModel`.

**Architecture:** Server: add the ID3v2 `COMM` frame + M4B `ldes` to the parser and a `description ?: comment` fallback in the Analyzer. Client: a reactive `ServerReachability` derived from the live SSE connection; all page state (reachability, download status, wifi/unmetered, derived flags) folded into `BookDetailViewModel`; `BookDetailPlatformActions` slimmed to side-effect actions; `DownloadButton` enforces `enabled` on the download action.

**Tech Stack:** Kotlin Multiplatform, Ktor, Koin, Compose Multiplatform, Kotest + Turbine + Mokkery, coroutines/Flow.

**Spec:** `docs/superpowers/specs/2026-06-03-book-detail-reachability-and-description-parity-design.md`

**Conventions:** TDD (RED→GREEN). Gitmoji + Conventional commits, **subject line only**, no body, no Claude attribution. Run `./gradlew spotlessApply` before committing Kotlin. Verify line numbers against current `main` before editing (PR #363 shifted some).

---

## File Structure

**Server (`:server` + `:contract`)**
- Modify `contract/.../domain/embeddedmeta/AudioTags.kt` — add `COMMENT_KEY` constant.
- Modify `server/.../embeddedmeta/format/mp3/Id3v2Reader.kt` — parse `COMM`.
- Modify `server/.../embeddedmeta/format/mp4/IlstReader.kt` — `ldes` + use `COMMENT_KEY`.
- Modify `server/.../scanner/pipeline/Analyzer.kt` — comment fallback.
- Modify `server/.../embeddedmeta/fixtures/BuildMp3File.kt` — add `commFrame` test helper.
- Test: `Mp3ParserTest.kt`, `Mp4ParserTest.kt`, `AnalyzerEnrichmentTest.kt`.

**Client (`:sharedLogic` + `:sharedUI`)**
- Create `sharedLogic/.../domain/repository/ServerReachability.kt` — interface + `Reachability`.
- Create `sharedLogic/.../data/repository/SseServerReachability.kt` — impl over `SyncEngineState`.
- Modify `sharedLogic/.../playback/PlaybackManagerImpl.kt` + `sharedLogic/jvmMain/.../JvmNetworkMonitor.kt` — `/health`→`/healthz`.
- Modify `sharedLogic/.../presentation/bookdetail/BookDetailViewModel.kt` — fold in state.
- Modify `sharedUI/.../features/bookdetail/BookDetailPlatformActions.kt` (+ Android impl + NoOp) — slim to actions.
- Modify `sharedUI/.../features/bookdetail/BookDetailScreen.kt` — read `uiState`.
- Modify `sharedUI/.../features/bookdetail/DownloadButton.kt` — enforce `enabled`.
- Modify Koin modules (DI for `ServerReachability`, VM ctor, platformActions).
- Test: `BookDetailViewModelTest.kt`, new `SseServerReachabilityTest.kt`.

---

## Phase 1 — Server: description parity

### Task 1: Parse the ID3v2 `COMM` frame into `custom["comment"]`

**Files:**
- Modify: `contract/src/commonMain/kotlin/com/calypsan/listenup/domain/embeddedmeta/AudioTags.kt`
- Modify: `server/src/main/kotlin/com/calypsan/listenup/server/embeddedmeta/format/mp3/Id3v2Reader.kt`
- Modify (test infra): `server/src/test/kotlin/com/calypsan/listenup/server/embeddedmeta/fixtures/BuildMp3File.kt`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/embeddedmeta/format/mp3/Mp3ParserTest.kt`

- [ ] **Step 1: Add the shared `COMMENT_KEY` constant.** In `AudioTags.kt`, add a companion to the `AudioTags` data class:

```kotlin
@Serializable
@SerialName("AudioTags")
data class AudioTags(
    // ... existing fields unchanged ...
    val custom: Map<String, String>,
) {
    companion object {
        /** The [custom] map key under which every format reader stores the file's
         *  comment tag (ID3v2 COMM, MP4 `©cmt`, Vorbis COMMENT). The Analyzer uses it
         *  as the `description` fallback, matching the Go reference. */
        const val COMMENT_KEY: String = "comment"
    }
}
```

- [ ] **Step 2: Add a `commFrame` test helper.** In `BuildMp3File.kt`, inside `Id3v2FrameSet` (next to `txxxFrame`, ~line 203), add:

```kotlin
/** COMM comments frame. Body = encoding + 3-byte language + shortDescription\0 + text. */
fun commFrame(
    text: String,
    language: String = "eng",
    shortDescription: String = "",
) {
    val payload =
        byteArrayOf(0x03) +
            language.toByteArray(Charsets.ISO_8859_1).copyOf(3) +
            shortDescription.toByteArray(Charsets.UTF_8) + 0x00.toByte() +
            text.toByteArray(Charsets.UTF_8)
    frames += "COMM" to payload
}
```

- [ ] **Step 3: Write the failing test.** In `Mp3ParserTest.kt` (inside the existing `FunSpec` block, alongside the TXXX test):

```kotlin
test("parse extracts COMM comment frame into tags.custom[COMMENT_KEY]") {
    val bytes =
        buildMp3File {
            id3v2 { commFrame(text = "A sweeping epic.", shortDescription = "") }
            mpegFrames(durationSeconds = 1)
        }
    val result = Mp3Parser().parse(FakeSeekableSource(bytes), bytes.size.toLong())
    result.shouldBeInstanceOf<AudioMetadataResult.Success>()
    result.metadata.tags.custom[AudioTags.COMMENT_KEY] shouldBe "A sweeping epic."
}
```

(Match the exact `Mp3Parser().parse(...)` call shape and result type used by the neighbouring tests in this file — read them first; the `FakeSeekableSource` and `AudioMetadataResult` names are defined in this test file. Add imports for `AudioTags` and `io.kotest.matchers.types.shouldBeInstanceOf` if absent.)

- [ ] **Step 4: Run the test — verify it FAILS.**

Run: `./gradlew :server:test --tests "*Mp3ParserTest*"`
Expected: FAIL — `custom[COMMENT_KEY]` is null (COMM not parsed yet).

- [ ] **Step 5: Implement COMM parsing.** In `Id3v2Reader.kt`, add a branch to the frame dispatch `when` (after the `frameId == "APIC"` branch, ~line 123):

```kotlin
frameId == "COMM" -> {
    handleComment(frameData, builder)
}
```

Then add the handler method (next to `handleTxxx`):

```kotlin
private fun handleComment(
    data: ByteArray,
    builder: AudioTagsBuilder,
) {
    // COMM: encoding(1) + language(3) + shortDescription\0 + commentText
    if (data.size < 5) return
    val encoding = data[0]
    val afterLanguage = data.copyOfRange(4, data.size)
    val (_, text) = splitNullTerminated(afterLanguage, encoding) ?: return
    val comment = text.trimNulls()
    if (comment.isEmpty()) return
    // First COMM wins; never clobber an explicit comment already seen.
    builder.custom.putIfAbsent(AudioTags.COMMENT_KEY, comment)
}
```

Add `import com.calypsan.listenup.domain.embeddedmeta.AudioTags` if not present.

- [ ] **Step 6: Run the test — verify it PASSES.**

Run: `./gradlew :server:test --tests "*Mp3ParserTest*"`
Expected: PASS.

- [ ] **Step 7: spotless + commit.**

```bash
./gradlew spotlessApply
git add contract/ server/
git commit -m "✨ feat(server): parse ID3v2 COMM frame into the comment tag"
```

---

### Task 2: M4B `ldes` long-description atom + use `COMMENT_KEY`

**Files:**
- Modify: `server/src/main/kotlin/com/calypsan/listenup/server/embeddedmeta/format/mp4/IlstReader.kt`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/embeddedmeta/format/mp4/Mp4ParserTest.kt`

- [ ] **Step 1: Write the failing test.** In `Mp4ParserTest.kt`, mirroring the file's existing `buildMp4File { ... }` tests (read one first for the exact parse-call shape):

```kotlin
test("parse maps ldes long-description atom to description") {
    val bytes =
        buildMp4File {
            ftyp()
            moov { /* match the minimal moov used by sibling tests */ }
            // udta { meta { tag("ldes", "The long description.") } } — match this file's DSL
        }
    val result = Mp4Parser().parse(FakeSeekableSource(bytes), bytes.size.toLong())
    result.shouldBeInstanceOf<AudioMetadataResult.Success>()
    result.metadata.tags.description shouldBe "The long description."
}
```

(Read an existing `Mp4ParserTest` case to copy the exact `buildMp4File`/`meta`/`tag` nesting and the parser call; `tag("ldes", "...")` uses the existing `IlstBuilder.tag` helper.)

- [ ] **Step 2: Run the test — verify it FAILS.**

Run: `./gradlew :server:test --tests "*Mp4ParserTest*"`
Expected: FAIL — `ldes` falls into the `else` branch (`custom["ldes"]`), so `description` is null.

- [ ] **Step 3: Implement.** In `IlstReader.kt`, in the `mapTextTag` `when` add an `ldes` branch right after the `desc` branch (~line 108), and switch `©cmt` to the constant (~line 104):

```kotlin
            "©cmt" -> builder.custom[AudioTags.COMMENT_KEY] = value

            "©des" -> builder.description = value

            "desc" -> builder.description = builder.description ?: value

            "ldes" -> builder.description = builder.description ?: value
```

Also update the freeform `"description"` branch (~line 199) and any other `custom["comment"]` literal in this file to `AudioTags.COMMENT_KEY` only if a `comment` literal exists there (it does not today — leave freeform as-is). Add `import com.calypsan.listenup.domain.embeddedmeta.AudioTags`.

- [ ] **Step 4: Run the test — verify it PASSES.**

Run: `./gradlew :server:test --tests "*Mp4ParserTest*"`
Expected: PASS.

- [ ] **Step 5: spotless + commit.**

```bash
./gradlew spotlessApply
git add server/
git commit -m "✨ feat(server): map M4B ldes atom to description, use COMMENT_KEY"
```

---

### Task 3: Analyzer `description ?: comment` fallback

**Files:**
- Modify: `server/src/main/kotlin/com/calypsan/listenup/server/scanner/pipeline/Analyzer.kt`
- Test: `server/src/test/kotlin/com/calypsan/listenup/server/scanner/pipeline/AnalyzerEnrichmentTest.kt`

- [ ] **Step 1: Write the failing test.** In `AnalyzerEnrichmentTest.kt`, mirroring the existing `buildMp3File`-fed enrichment tests (e.g. "embedded ID3v2 title enriches AnalyzedBook"):

```kotlin
test("embedded comment is the description fallback when no description tag") {
    val fixture =
        audioLibrary {
            // one book whose only MP3 carries a COMM frame and NO TXXX description
            // — copy the fixture/Analyzer wiring from the title-enrichment test above,
            // building the mp3 with: id3v2 { textFrame("TIT2", "Book"); commFrame(text = "From the comment.") }
        }
    val book = /* run Analyzer(...).analyze(...) as the sibling test does */
    book.description shouldBe "From the comment."
}

test("explicit description still wins over comment") {
    // mp3 with BOTH txxxFrame("description", "Real desc") and commFrame(text = "Comment")
    // assert book.description shouldBe "Real desc"
}
```

(Copy the exact `audioLibrary { }` fixture construction and `Analyzer(...)` invocation from the existing enrichment tests in this file — they show the full wiring.)

- [ ] **Step 2: Run — verify it FAILS.**

Run: `./gradlew :server:test --tests "*AnalyzerEnrichmentTest*"`
Expected: FAIL — first test's `description` is null (no comment fallback in the chain).

- [ ] **Step 3: Implement.** In `Analyzer.kt`, change the description line (~line 330) to:

```kotlin
            description =
                metadata?.description
                    ?: embedded?.tags?.description
                    ?: embedded?.tags?.custom?.get(AudioTags.COMMENT_KEY)
                    ?: sidecar?.description,
```

Add `import com.calypsan.listenup.domain.embeddedmeta.AudioTags` if absent.

- [ ] **Step 4: Run — verify it PASSES.**

Run: `./gradlew :server:test --tests "*AnalyzerEnrichmentTest*"`
Expected: PASS (both new tests).

- [ ] **Step 5: Full server suite + commit.**

```bash
./gradlew :server:test
./gradlew spotlessApply
git add server/
git commit -m "🐛 fix(server): fall back to comment tag for book description"
```

---

## Phase 2 — Client: reactive reachability

### Task 4: `ServerReachability` derived from the SSE connection

**Files:**
- Create: `sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/domain/repository/ServerReachability.kt`
- Create: `sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/data/repository/SseServerReachability.kt`
- Modify (DI): `sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/di/ClientSyncRenovationModule.kt`
- Test: `sharedLogic/src/commonTest/kotlin/com/calypsan/listenup/client/data/repository/SseServerReachabilityTest.kt`

- [ ] **Step 1: Define the interface + type.** Create `ServerReachability.kt`:

```kotlin
package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.StateFlow

/** Whether the active server is currently reachable, for gating streaming/download UI. */
sealed interface Reachability {
    /** Server connection is live. */
    data object Reachable : Reachability

    /** Server connection is down. */
    data object Unreachable : Reachability

    /** Not yet determined (e.g. connecting at startup) — treat optimistically. */
    data object Unknown : Reachability
}

/** Reactive server reachability, derived from the live SSE firehose connection. */
interface ServerReachability {
    val state: StateFlow<Reachability>
}
```

- [ ] **Step 2: Write the failing test.** Create `SseServerReachabilityTest.kt`:

```kotlin
package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.Reachability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SseServerReachabilityTest :
    FunSpec({
        test("maps SSE Connected to Reachable") {
            runTest {
                val engineState = SyncEngineState()
                val reachability = SseServerReachability(engineState, backgroundScope)
                engineState.setConnection(ConnectionState.Connected(lastEventId = 1L))
                reachability.state.test {
                    // initial Disconnected(null) maps to Unreachable, then Reachable
                    awaitItem() // skip whichever initial value is replayed
                    skipItemsUntil { it == Reachability.Reachable }
                }
            }
        }

        test("maps SSE Disconnected to Unreachable and Connecting to Unknown") {
            runTest {
                val engineState = SyncEngineState()
                val reachability = SseServerReachability(engineState, backgroundScope)
                engineState.setConnection(ConnectionState.Connecting)
                reachability.state.test { skipItemsUntil { it == Reachability.Unknown } }
                engineState.setConnection(ConnectionState.Disconnected("closed"))
                reachability.state.test { skipItemsUntil { it == Reachability.Unreachable } }
            }
        }
    })

private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.skipItemsUntil(predicate: (T) -> Boolean) {
    while (true) if (predicate(awaitItem())) return
}
```

(Confirm `SyncEngineState.setConnection(...)` and `ConnectionState` are the actual public names in `data/sync/SyncEngineState.kt`; adjust if the mutator differs. Verify `app.cash.turbine` is the Turbine package id in the version catalog.)

- [ ] **Step 3: Run — verify it FAILS (won't compile: `SseServerReachability` missing).**

Run: `./gradlew :sharedLogic:jvmTest --tests "*SseServerReachabilityTest*"`
Expected: FAIL — unresolved `SseServerReachability`.

- [ ] **Step 4: Implement.** Create `SseServerReachability.kt`:

```kotlin
package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Derives [Reachability] from the live SSE firehose connection ([SyncEngineState]).
 * A brief debounce absorbs transient reconnect flaps so the UI doesn't flicker.
 */
class SseServerReachability(
    engineState: SyncEngineState,
    scope: CoroutineScope,
) : ServerReachability {
    override val state: StateFlow<Reachability> =
        engineState
            .observe()
            .map { snapshot ->
                when (snapshot.connection) {
                    is ConnectionState.Connected -> Reachability.Reachable
                    ConnectionState.Connecting -> Reachability.Unknown
                    is ConnectionState.Disconnected -> Reachability.Unreachable
                }
            }.debounce(DEBOUNCE_MILLIS)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, Reachability.Unknown)

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
    }
}
```

(If `ConnectionState` subtypes differ — e.g. `Connected` is a `data class` — keep the `is`/object matching consistent with the sealed definition. `@OptIn(FlowPreview::class)` may be required for `debounce`; add it on the property/class if the compiler asks.)

- [ ] **Step 5: Run — verify it PASSES.**

Run: `./gradlew :sharedLogic:jvmTest --tests "*SseServerReachabilityTest*"`
Expected: PASS.

- [ ] **Step 6: Register in DI.** In `ClientSyncRenovationModule.kt`, near the `single { SyncEngineState() }` line, add:

```kotlin
single<ServerReachability> { SseServerReachability(get(), get(qualifier = named("appScope"))) }
```

Add imports for `ServerReachability`, `SseServerReachability`, and `org.koin.core.qualifier.named` if absent. (Confirm the appScope qualifier name matches the one used by `SyncEngine` in this module.)

- [ ] **Step 7: spotless + commit.**

```bash
./gradlew spotlessApply
git add sharedLogic/
git commit -m "✨ feat(shared): ServerReachability derived from the SSE connection"
```

---

### Task 5: Fix the `/health` → `/healthz` endpoint bug

**Files:**
- Modify: `sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/playback/PlaybackManagerImpl.kt:408`
- Modify: `sharedLogic/src/jvmMain/kotlin/com/calypsan/listenup/client/data/repository/JvmNetworkMonitor.kt:98`

- [ ] **Step 1: Fix both call sites.** Change `"$url/health"` → `"$url/healthz"` in `PlaybackManagerImpl.kt`, and `"$serverUrl/health"` → `"$serverUrl/healthz"` in `JvmNetworkMonitor.kt`. (Server route is `/healthz` per `server/.../routes/HealthRoutes.kt`.)

- [ ] **Step 2: Verify compile.**

Run: `./gradlew :sharedLogic:compileKotlinJvm`
Expected: SUCCESS.

- [ ] **Step 3: Commit.**

```bash
git add sharedLogic/
git commit -m "🐛 fix(shared): health check hits /healthz, not the nonexistent /health"
```

---

## Phase 3 — Client: consolidate state into `BookDetailViewModel`

### Task 6: Fold reachability + download + wifi state into the ViewModel

**Files:**
- Modify: `sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/presentation/bookdetail/BookDetailViewModel.kt`
- Test: `sharedLogic/src/commonTest/kotlin/com/calypsan/listenup/client/presentation/bookdetail/BookDetailViewModelTest.kt`

**Context:** `Ready` currently has no download/reachability fields. We add them and `combine` the new sources into the load pipeline. New ctor deps: `DownloadRepository`, `ServerReachability`, `NetworkMonitor`, `SettingsRepository`, and a `playbackAvailable: Boolean` capability flag.

- [ ] **Step 1: Extend `BookDetailUiState.Ready`.** Add these fields (with defaults) to the `Ready` data class:

```kotlin
        val downloadStatus: BookDownloadStatus = BookDownloadStatus.NotDownloaded(""),
        val isPlaybackAvailable: Boolean = true,
        val canPlay: Boolean = true,
        val canDownload: Boolean = false,
        val showServerWarning: Boolean = false,
        val isWaitingForWifi: Boolean = false,
```

Add `import com.calypsan.listenup.client.domain.model.BookDownloadStatus`.

- [ ] **Step 2: Write the failing test.** In `BookDetailViewModelTest.kt`, add (using the file's existing fakes/builders — read them first; if the test uses kotlin-test, add these as Kotest only if the file is already Kotest, otherwise match the file's existing framework to avoid a mixed-framework file):

```kotlin
test("downloaded book stays playable when server is unreachable") {
    // reachability = Unreachable, downloadStatus = Completed
    // → state.canPlay == true, canDownload == false, showServerWarning == false
}

test("not-downloaded book: unreachable disables play + download and shows warning") {
    // reachability = Unreachable, downloadStatus = NotDownloaded
    // → canPlay == false, canDownload == false, showServerWarning == true
}

test("reachable + not downloaded: can play and can download") {
    // reachability = Reachable, downloadStatus = NotDownloaded
    // → canPlay == true, canDownload == true, showServerWarning == false
}

test("unknown reachability is optimistic: can play, cannot download") {
    // reachability = Unknown, downloadStatus = NotDownloaded
    // → canPlay == true, canDownload == false, showServerWarning == false
}
```

Each test: construct the VM with fakes that emit the given reachability + download status, call `loadBook(id)`, then assert on `state.value as BookDetailUiState.Ready` (use Turbine `state.test { }` and skip to the `Ready` emission). Build a `FakeServerReachability(MutableStateFlow<Reachability>)`, and use the existing fake `DownloadRepository`/`BookRepository` in the test file (or add minimal fakes following the file's pattern).

- [ ] **Step 3: Run — verify it FAILS to compile (new ctor params / fields missing).**

Run: `./gradlew :sharedLogic:jvmTest --tests "*BookDetailViewModelTest*"`
Expected: FAIL.

- [ ] **Step 4: Add ctor deps + combine pipeline.** Update the `BookDetailViewModel` constructor to add:

```kotlin
    private val downloadRepository: DownloadRepository,
    private val serverReachability: ServerReachability,
    private val networkMonitor: NetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val playbackAvailable: Boolean,
```

Replace the body of `loadBookFlow(bookId)` so the final `emitAll` combines the book detail with the new reactive sources:

```kotlin
        emitAll(
            combine(
                bookRepository.observeBookDetail(bookId),
                downloadRepository.observeBookStatus(BookId(bookId)),
                serverReachability.state,
                networkMonitor.isOnUnmeteredNetworkFlow,
                settingsRepository.observeWifiOnlyDownloads(),
            ) { detail, downloadStatus, reachability, unmetered, wifiOnly ->
                if (detail == null) {
                    BookDetailUiState.Error("Book not found")
                } else {
                    val isFullyDownloaded = downloadStatus is BookDownloadStatus.Completed
                    val isWaitingForWifi =
                        downloadStatus.isQueued() && wifiOnly && !unmetered
                    buildReady(detail, chapters, position).copy(
                        downloadStatus = downloadStatus,
                        isPlaybackAvailable = playbackAvailable,
                        canPlay = isFullyDownloaded || reachability != Reachability.Unreachable,
                        canDownload = playbackAvailable && reachability == Reachability.Reachable,
                        showServerWarning = reachability == Reachability.Unreachable && !isFullyDownloaded,
                        isWaitingForWifi = isWaitingForWifi,
                    )
                }
            },
        )
```

Add imports: `BookId`, `Reachability`, `ServerReachability`, `DownloadRepository`, `NetworkMonitor`, `SettingsRepository`, `BookDownloadStatus`, `kotlinx.coroutines.flow.combine`. Confirm the exact names `isOnUnmeteredNetworkFlow`, `observeWifiOnlyDownloads()`, and `BookDownloadStatus.isQueued()` against their interfaces; adjust to the real signatures. (`isQueued()` is used by `BookDetailScreen` today — reuse the same extension.)

- [ ] **Step 5: Run — verify it PASSES.**

Run: `./gradlew :sharedLogic:jvmTest --tests "*BookDetailViewModelTest*"`
Expected: PASS.

- [ ] **Step 6: spotless + commit.**

```bash
./gradlew spotlessApply
git add sharedLogic/
git commit -m "✨ feat(shared): fold reachability + download state into BookDetailViewModel"
```

---

### Task 7: Wire the new ViewModel dependencies in DI

**Files:**
- Modify: the Koin module declaring `BookDetailViewModel` (find via `grep -rn "BookDetailViewModel" sharedLogic/src/commonMain/.../di/`), e.g. a `viewModelOf(::BookDetailViewModel)` or `viewModel { BookDetailViewModel(...) }`.
- Modify: the platform DI that provides the `playbackAvailable` flag (Android = true; desktop = false).

- [ ] **Step 1: Provide the `playbackAvailable` capability.** In the Android Koin module add `single(named("playbackAvailable")) { true }`; in the desktop module add `single(named("playbackAvailable")) { false }`. (Match the module style used for other platform singles.)

- [ ] **Step 2: Update the VM registration.** If it uses `viewModelOf(::BookDetailViewModel)`, switch to an explicit builder so the new deps + the qualified boolean resolve:

```kotlin
viewModel {
    BookDetailViewModel(
        bookRepository = get(),
        tagRepository = get(),
        playbackPositionRepository = get(),
        userRepository = get(),
        shelfRepository = get(),
        addBooksToShelfUseCase = get(),
        createShelfUseCase = get(),
        errorBus = get(),
        downloadRepository = get(),
        serverReachability = get(),
        networkMonitor = get(),
        settingsRepository = get(),
        playbackAvailable = get(qualifier = named("playbackAvailable")),
    )
}
```

(Keep the existing arg list in sync with the actual constructor; this shows the full target shape.)

- [ ] **Step 3: Verify DI graph compiles + module verify passes.**

Run: `./gradlew :sharedLogic:jvmTest --tests "*ModuleVerif*" --tests "*Koin*"`
Expected: PASS (or run the relevant `module.verify()` test for the touched module).

- [ ] **Step 4: Commit.**

```bash
./gradlew spotlessApply
git add sharedLogic/
git commit -m "🔧 chore(shared): wire BookDetailViewModel's reachability/download deps in Koin"
```

---

## Phase 4 — Client: slim platform actions + UI

### Task 8: Slim `BookDetailPlatformActions` to side-effect actions

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/features/bookdetail/BookDetailPlatformActions.kt`
- Modify: `sharedUI/src/androidMain/kotlin/com/calypsan/listenup/client/features/bookdetail/AndroidBookDetailPlatformActions.kt`
- Modify: the DI registration of `AndroidBookDetailPlatformActions` (in `sharedUI/.../ListenUp.kt`).

- [ ] **Step 1: Reduce the interface.** Edit `BookDetailPlatformActions` to keep ONLY the action members and delete the state members:

```kotlin
interface BookDetailPlatformActions {
    suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome>
    suspend fun cancelDownload(bookId: BookId)
    suspend fun deleteDownload(bookId: BookId)
    fun playBook(bookId: BookId)
    fun shareText(text: String, url: String)
}
```

Delete `isPlaybackAvailable`, `observeBookStatus`, `observeWifiOnlyDownloads`, `observeIsOnUnmeteredNetwork`, `checkServerReachable`. Update `NoOpBookDetailPlatformActions` to drop the same members.

- [ ] **Step 2: Trim the Android impl.** In `AndroidBookDetailPlatformActions`, remove the deleted overrides and any now-unused ctor params (`networkMonitor`, `playbackManager` if `checkServerReachable` was its only use, `localPreferences` if only used for wifi-only). Keep `downloadManager` (for download actions) + `nowPlayingViewModel`/`context` (playback/share). Update its DI in `ListenUp.kt` to drop the removed args.

- [ ] **Step 3: Verify compile (will fail in `BookDetailScreen` — fixed in Task 9).**

Run: `./gradlew :sharedLogic:compileKotlinJvm`
Expected: SUCCESS for `:sharedLogic`. (`:sharedUI` compile is finished in Task 9.)

- [ ] **Step 4: Commit (with Task 9, since the screen must compile).** Defer the commit to the end of Task 9 so the tree compiles.

---

### Task 9: Make `BookDetailScreen` read `uiState` only

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/features/bookdetail/BookDetailScreen.kt`

- [ ] **Step 1: Remove the inline state.** Delete the inline `downloadStatus`/`wifiOnly`/`unmetered` collectors (~lines 174-189), the `isServerReachable` `remember` + `LaunchedEffect { checkServerReachable() }` (~lines 194-199), and the inline `canPlay`/`canDownload`/`showServerWarning`/`isFullyDownloaded` computations (~lines 195-211).

- [ ] **Step 2: Read them from `state`.** Where the screen builds its content, pull the values from the `Ready` state already collected at line 105 (`val state by viewModel.state.collectAsStateWithLifecycle()`). For the `Ready` branch, pass `state.canPlay`, `state.canDownload`, `state.showServerWarning`, `state.downloadStatus`, `state.isWaitingForWifi`, `state.isPlaybackAvailable` into `BookDetailContent` / `PrimaryActionsSection` instead of the deleted locals. Keep the action lambdas calling `platformActions.playBook/downloadBook/cancelDownload/deleteDownload/shareText`.

- [ ] **Step 3: Compile `:sharedUI` (Android + desktop).**

Run: `./gradlew :androidApp:assembleDebug :sharedUI:compileKotlinDesktop`
Expected: SUCCESS. (If desktop fails on the pre-existing `PlatformModule` Room drift unrelated to these files, note it and rely on the Android compile — do not fix unrelated drift here.)

- [ ] **Step 4: Commit Tasks 8 + 9 together.**

```bash
./gradlew spotlessApply
git add sharedUI/
git commit -m "♻️ refactor(sharedUI): Book Detail reads one uiState; platform actions slimmed"
```

---

### Task 10: Enforce `enabled` on the download action in `DownloadButton`

**Files:**
- Modify: `sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/features/bookdetail/DownloadButton.kt`

- [ ] **Step 1: Gate the download `IconButton`s.** Add `enabled = enabled` to the two `IconButton(onClick = onDownloadClick)` calls (the `NotDownloaded` state ~line 93 and the `Failed`/retry state ~line 157). **Leave the cancel (`onCancelClick`, ~line 103) and delete (`onDeleteClick`, ~line 145) `IconButton`s unchanged** — cancel/delete need no server.

```kotlin
IconButton(onClick = onDownloadClick, enabled = enabled) {
    Icon(
        Icons.Outlined.Download,
        contentDescription = stringResource(Res.string.book_detail_download_book),
        tint = contentColor,
    )
}
```

- [ ] **Step 2: Confirm the caller passes `canDownload`.** In `PrimaryActionsSection` (the `DownloadButton(...)` call), ensure `enabled = canDownload` is wired from the screen's `state.canDownload` (Task 9). It already passes `enabled = downloadEnabled` — confirm `downloadEnabled` traces to `state.canDownload`.

- [ ] **Step 3: Compile + commit.**

```bash
./gradlew :androidApp:assembleDebug
./gradlew spotlessApply
git add sharedUI/
git commit -m "🐛 fix(sharedUI): DownloadButton blocks the download tap when disabled"
```

---

## Phase 5 — Verify & finish

### Task 11: Full gate + on-device verification

- [ ] **Step 1: Run the full local gate.**

Run: `./gradlew spotlessCheck detekt :sharedLogic:jvmTest :server:test :androidApp:assembleDebug --no-daemon`
Expected: PASS (the `MulticastMdnsResponderTest` env failure and the flaky `RestoreOrchestratorTest` are pre-existing/non-blocking — re-run those classes in isolation if they fail).

- [ ] **Step 2: On-device (android CLI).** Install and drive:

```bash
android run --apks=androidApp/build/outputs/apk/debug/app-debug.apk --device=emulator-5554
```

Verify by `android screen capture --annotate` + `android layout`:
- Server reachable → Play enabled, Download enabled.
- Stop the server → Play greys out + "Server is unreachable" banner shows; Download greys out and the tap does nothing.
- A fully-downloaded book → Play stays enabled with the server stopped.
- Restart the server → both recover without flicker.
- A book whose description lived in a comment tag now shows its description (re-scan a library with such a file).

- [ ] **Step 3: Push + open PR.**

```bash
GH_TOKEN=<pat> git -c credential.helper='!f(){ echo username=x; echo password=$GH_TOKEN; };f' push -u origin fix/book-detail-reachability-description-parity
GH_TOKEN=<pat> /usr/bin/gh pr create --base main --title "🐛 fix(client): Book Detail reachability, download gating & description parity" --body "<summary>"
```

---

## Notes for the implementer
- Verify all `~line NNN` references against current `main` before editing — PR #363 shifted some files.
- New tests are **Kotest FunSpec**. If a file you extend is still on `kotlin-test`, match its existing framework rather than mixing in one file.
- `gh` is aliased through a broken 1Password plugin — use `/usr/bin/gh` with `GH_TOKEN`.
- Konsist guards run in `:sharedLogic:jvmTest`: keep new commonMain code free of `Dispatchers.IO` (use `IODispatcher`) and `java.*` imports.
