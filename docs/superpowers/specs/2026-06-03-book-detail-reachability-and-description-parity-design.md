# Book Detail: server reachability, download gating & description parity

**Date:** 2026-06-03
**Branch:** `fix/book-detail-reachability-description-parity` (off `main` @ `6f9a3c35`)
**Status:** Design approved; spec for review before planning.

## Problem

Three defects on the Book Detail screen, plus the structural drift that produced them:

1. **Descriptions missing for some books.** The Kotlin scanner does not match the Go
   server's `description → comment` fallback, so audiobooks whose blurb lives in a
   *comment* tag come through blank.
2. **Play button always greyed out, "Server is unreachable".** The reachability check
   hits `/health`; the server serves `/healthz`. It is therefore always a 404 → always
   "unreachable" → play disabled for every book (even when the server is reachable). A
   downloaded book should always play regardless.
3. **Download button never disabled.** `DownloadButton`'s `enabled` flag only tints
   colours; the `IconButton`'s `onClick` fires regardless. There is also no connectivity
   gate, so a user can trigger a download with no server to download from.

**Structural root cause (client).** Book Detail state is split-brain: the
`BookDetailViewModel` owns book/tag/shelf/position state, but reachability, download
status, wifi/unmetered, and the `canPlay`/`canDownload`/`showServerWarning` flags are
computed *inline in the composable* via `BookDetailPlatformActions`. Reachability being a
one-shot inline `LaunchedEffect` check is what let it rot. The fix consolidates all page
state into the ViewModel.

## Decisions (locked)

- **Reachability is reactive, derived from the existing SSE connection** (`SyncEngineState.connection`),
  not a new `/healthz` poller. The firehose is a live, keep-alived server connection — the
  truest reachability signal — and `SyncEngineState` is already a Koin singleton.
- **Download is disabled + enforced when unreachable** (downloads pull audio *from* the
  server, so they genuinely cannot start offline). Cancelling an in-flight download and
  deleting a finished one stay enabled — neither needs the server.
- **Full ViewModel consolidation, keep the good bones.** All page state moves into
  `BookDetailViewModel`; the composable becomes pure presentation reading one `uiState`;
  `BookDetailPlatformActions` slims to side-effect actions only. The VM's existing
  sealed-state / `flatMapLatest` / `stateIn` / tag-shelf-position logic is preserved, not
  rewritten.
- **Description fallback reuses `custom["comment"]`** (no new field on the `AudioTags` wire
  contract). YAGNI: nothing displays comment as a distinct field today.
- **One branch, one PR**, server + client together.

## Architecture

### A. Server — description parity with Go *(`:server` scanner)*

Match Go's `getDescription()` = `description ?: comment` across formats.

- **`embeddedmeta/format/mp3/Id3v2Reader.kt`** — parse the `COMM` (comments) frame, which
  is currently ignored entirely. COMM layout: 1 encoding byte, 3-byte language, a
  null-terminated short description, then the comment text. Store the comment text into
  `builder.custom[COMMENT_KEY]` (first-wins), mirroring where M4B's `©cmt` already lands.
- **`embeddedmeta/format/mp4/IlstReader.kt`** — `©cmt → custom["comment"]` already works.
  Add the `ldes` (long-description) atom → `builder.description` (first-wins) for M4B
  audiobooks. (`©des`/`desc` handling unchanged.)
- **`scanner/pipeline/Analyzer.kt`** (the `compose(...)` description line) — extend the
  chain to:
  `metadata?.description ?: embedded?.tags?.description ?: embedded?.tags?.custom?.get(COMMENT_KEY) ?: sidecar?.description`.
- Introduce a single shared `COMMENT_KEY = "comment"` constant so the custom-map key is not
  a magic string repeated across the readers and the Analyzer.
- **Non-goal:** Vorbis/FLAC/Ogg readers don't exist in the Kotlin port yet; when added they
  follow the same `DESCRIPTION → COMMENT` rule. ID3v1's comment already maps to
  `description` and is unchanged.

### B. Client — reactive `ServerReachability`

A small abstraction derived from the SSE connection state.

- `domain/repository/ServerReachability.kt`: `interface ServerReachability { val state: StateFlow<Reachability> }`
  with `sealed interface Reachability { Reachable; Unreachable; Unknown }`.
- Impl (in `data/`, Koin singleton) maps `SyncEngineState.observe()`'s `ConnectionState`:
  `Connected → Reachable`, `Connecting → Unknown`, `Disconnected → Unreachable`, with a
  short debounce so transient reconnects don't flicker the UI.
- **Endpoint hygiene (separate, real bug):** fix `/health → /healthz` in
  `PlaybackManagerImpl.isServerReachable()` and `JvmNetworkMonitor` (desktop device-online
  still uses it). If `isServerReachable()` is orphaned once Book Detail moves to
  `ServerReachability`, remove it (clean up our own mess); otherwise leave it correct.

### C. Client — consolidate state into `BookDetailViewModel`

Inject the now-reactive sources and `combine` them into the existing `Ready` state:

- New ctor deps (all commonMain interfaces): `DownloadRepository` (`observeBookStatus`),
  `ServerReachability`, `NetworkMonitor` (`isOnUnmeteredNetworkFlow`), `SettingsRepository`
  (wifi-only preference), and an injected **playback-capability** flag (true on Android,
  false on the desktop no-op) replacing `isPlaybackAvailable`.
- `BookDetailUiState.Ready` gains: `downloadStatus`, `canPlay`, `canDownload`,
  `showServerWarning`, `isWaitingForWifi`, `isPlaybackAvailable`. Derivations:
  - `canPlay = isFullyDownloaded || reachability != Unreachable` — optimistic on `Unknown`
    so a fast connection never flickers the button.
  - `canDownload = isPlaybackAvailable && reachability == Reachable`.
  - `showServerWarning = reachability == Unreachable && !isFullyDownloaded`.
- The main load pipeline `combine`s `observeBookDetail(id)` with `observeBookStatus(id)`,
  the reachability flow, the unmetered flow, and the wifi-only flow. Book switching stays
  `flatMapLatest`-driven; subscription lifetime stays `stateIn(WhileSubscribed)` /
  `viewModelScope`.
- The composable drops the inline `checkServerReachable()` `LaunchedEffect`, the
  `isServerReachable`/`downloadStatus`/`wifiOnly`/`unmetered` collectors, and all derived
  booleans — it reads them from `uiState`. It calls `BookDetailPlatformActions` only for
  side effects.

### D. Client — slim `BookDetailPlatformActions` + fix `DownloadButton`

- `BookDetailPlatformActions` keeps only side-effect actions: `downloadBook`,
  `cancelDownload`, `deleteDownload`, `playBook`, `shareText`. The state-providing members
  (`observeBookStatus`, `observeWifiOnlyDownloads`, `observeIsOnUnmeteredNetwork`,
  `checkServerReachable`, `isPlaybackAvailable`) are removed — their data now flows through
  the VM. The Android impl and `NoOpBookDetailPlatformActions` shrink accordingly; the
  playback-capability flag moves to a DI-provided value.
- `DownloadButton.kt` — apply `enabled` to the **download** `IconButton`(s) so taps are
  blocked when `!canDownload`. **Cancel and delete `IconButton`s stay always-enabled**
  (no server needed to cancel/delete). Container tint already reflects `enabled`.

## Testing

TDD, Kotest (canonical), RED-first.

- **Server:** `Mp3ParserTest` (COMM frame → comment → description fallback),
  `Mp4ParserTest` (`©cmt` → comment fallback; `ldes` → description),
  `AnalyzerEnrichmentTest` (full `description ?: comment` chain, and that explicit
  `description` still wins over `comment`).
- **Client:** extend `BookDetailViewModelTest` (Kotest + Turbine) across the
  reachability × download-status matrix — `canPlay`/`canDownload`/`showServerWarning` for
  {Reachable, Unreachable, Unknown} × {NotDownloaded, InProgress, Completed}; assert a
  downloaded book stays playable when `Unreachable`. New `ServerReachability` mapping test
  (`ConnectionState` → `Reachability`, including debounce). A `DownloadButton` interaction
  test if the existing UI-test harness supports it.
- **On-device** via the `android` CLI (`android run` + `android screen capture --annotate`):
  server up → play + download enabled; server stopped → play greys out with the banner and
  download is unclickable; downloaded book plays offline; reconnect → both recover without
  flicker.

## Out of scope / non-goals

- No "queue download for later / auto-start on reconnect" — explicitly deferred; downloads
  are disabled while unreachable.
- No new `comment` field on the `AudioTags` contract.
- No Vorbis/FLAC/Ogg reader work.
- No ground-up VM rewrite; existing tag/shelf/position logic is preserved.

## Risks

- **Reachability false-negatives.** If the SSE engine isn't connected for a non-network
  reason (auth transient, engine not yet started), `Unreachable`/`Unknown` could disable
  play for a streamable book. Mitigated by treating `Unknown` as optimistic and by the
  debounce; the downloaded-book bypass means offline content is never blocked.
- **`platformActions` slimming churn.** Removing members ripples to the Android impl, the
  no-op impl, and DI. Compile errors guide the cascade; surface area is small.
