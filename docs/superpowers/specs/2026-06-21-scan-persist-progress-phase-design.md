# Scan persist-progress phase — design

## Problem

During the initial library setup, the scan progress screen freezes at 100% for ~25 seconds before
advancing, with no indication that work is happening.

The progress bar is bound to `booksAnalyzed / booksTotal`
(`ScanProgressState.progressFraction`), so it reaches 100% the instant **analysis** finishes. The
Scanner then hands its `ScanResult` to `BookPersister`, which writes every book to the DB — the
~25s — emitting **no events** until the terminal `ScanEvent.Completed`. The client has nothing to
react to, so the determinate bar sits frozen at 100%. (After `Completed`, the client already shows an
animated "Finishing up — almost there" state during its reconcile; that window is fine. Only the
server-persist window is silent.)

## Goal

Make persistence a visible, honest phase: the same progress bar the user already likes keeps moving
through a second labelled pass — "Saving library… N of M" — instead of freezing. No screen redesign;
reuse the existing components.

## Design

### 1. Contract — `ScanPhase.PERSISTING`

Add one enum value to `contract/.../api/dto/scanner/ScanPhase.kt`:

```
WALKING, GROUPING, ANALYZING, DIFFING, PERSISTING, COMPLETED
```

`ScanEvent.Progress` already carries everything we need (`phase`, `booksAnalyzed`, `booksTotal`, …),
so no event-shape change.

### 2. Server — `BookPersister.persistAll` emits PERSISTING progress

`persistAll` already loops over the changed books and tracks a `persisted` count; it has the
`eventBus` it uses for `Completed`, plus `correlationId`/`libraryId`.

- Compute `toPersist` = number of `Added`/`Modified`/`Moved` changes (the books that go through
  `resolveOrInsert`). `Removed` changes are tombstones, not persisted books, so they're excluded.
- Emit `ScanEvent.Progress(phase = PERSISTING, booksAnalyzed = persistedSoFar, booksTotal = toPersist, …)`:
  - **once immediately** at persist start (`persistedSoFar = 0`) so the UI leaves the 100%-analyze
    state right away;
  - **throttled** as books land — count-based: emit every `max(1, toPersist / 100)` books, so at most
    ~100 events regardless of library size (deterministic, no clock dependency, test-friendly);
  - the running `persisted` count drives `booksAnalyzed`.
- `totalDurationMs` is carried from the result where cheap; `authorsMatched`/`recentBooks` aren't
  available in `BookPersister` (Scanner aggregates) and are left at defaults — the **client preserves
  them** (see §3), so the stats panel doesn't blank during persist.
- Emit on the existing `eventBus` (`MutableSharedFlow<ScanEvent>`). `FirehoseSuppressed` (active for
  full scans) gates the cross-domain **ChangeBus firehose**, not the scan `eventBus`, so progress
  still flows during a suppressed full scan. (Verify in implementation.)
- The existing `Completed` still fires after the loop; the client's post-`Completed` "Finishing up"
  covers the reconcile.
- If `toPersist == 0` (nothing changed), skip PERSISTING emission entirely — no 0/0 bar.

### 3. Client — state, display name + stat preservation

- `ScanProgressState.phaseDisplayName`: add `"persisting" -> "Saving library"`.
- `ScanProgressState.savingLabel`: new property → `"Saving $books of $booksTotal"` (the label under the
  bar during persist).
- `SyncRepositoryImpl.applyScanEvent` (the `ScanEvent.Progress` branch): for the PERSISTING phase,
  **preserve the prior `ScanProgressState`** and update only `phase`, `books` (= persisted), and
  `booksTotal` (= toPersist) — e.g. `getProgress()?.copy(phase = "persisting", books = …, booksTotal = …)`.
  If there is no prior state (defensive — ANALYZING always precedes PERSISTING in a real scan), fall
  back to building a fresh state from the event as the other phases do. This keeps the
  hours/authors/recent-books carousel on screen while `progressFraction` (`books/booksTotal`) climbs
  0→100% under the "Saving library" label. (Non-PERSISTING phases keep building a fresh state from the
  event, unchanged.)

### 4. Screen — `LibraryScanScreen` (added during implementation)

The original spec assumed the screen needed no change. It does, because the determinate path:
- hard-codes the subtitle *"Analyzing your audiobooks…"*, and
- shows *"X% complete · about N min left"* where the ETA is computed from the **whole-scan start** —
  during persist that elapsed time includes the ~25s analyze, so the ETA would be bogus.

Two localized tweaks (no redesign; existing Expressive components):
- **Subtitle:** phase-aware — `"Saving your library — almost done."` during persist, else the existing
  analyzing copy.
- **Progress label (`ScanProgressLabel`):** early-return for the persist phase showing
  `progress.savingLabel` ("Saving N of M") instead of the percentage/ETA — the persist phase has no
  meaningful per-scan ETA. The file-count row and stats panel stay (state is preserved), so only the
  bar restarts and the label/subtitle reflect the new step.

### Resulting UX

`Discovering files` (indeterminate) → `Analyzing` (bar climbs 0→100%) → **`Saving library` (bar
climbs 0→100% again, live N of M)** → `Finishing up` (animated, reconcile) → advance. No frozen
window; every phase communicates.

## Edge cases

- **Incremental (subtree) scans:** also emit PERSISTING (few books, fast) — consistent, harmless.
- **Failed / OOM books:** `persisted` advances only on success; on OOM the loop stops and `Completed`
  fires — the bar stops mid-"Saving" then transitions to "Finishing up". Acceptable.
- **Empty changes:** `toPersist == 0` → no PERSISTING events → straight to `Completed`.

## Testing (TDD)

- **Server (`BookPersisterTest`):** a `persist(ScanResult)` with N changed books asserts the
  `eventBus` receives ≥1 `ScanEvent.Progress` with `phase == PERSISTING`, `booksTotal == N`, a first
  event at `booksAnalyzed == 0`, and a final reaching `booksAnalyzed == N` — ordered before
  `Completed`. Reuses the existing `eventBus.replayCache` capture pattern.
- **Client (`:sharedLogic`):** `phaseDisplayName` maps `"persisting" -> "Saving library"`; and an
  `applyScanEvent` test feeds an ANALYZING event then a PERSISTING event and asserts the prior stats
  (durationMs/authors/recentBooks) are retained while `books`/`booksTotal`/`phase` update.

## Out of scope

- The post-`Completed` reconcile window (already animated). No change.
- Determinate progress for the reconcile itself (separate, smaller concern).
