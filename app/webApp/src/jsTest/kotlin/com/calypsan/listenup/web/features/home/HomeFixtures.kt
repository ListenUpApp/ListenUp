package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.core.ShelfId

private const val HOUR_MS = 3_600_000L

/** One hydrated Continue Listening slot, roughly half finished by default. */
internal fun continuing(
    id: String,
    title: String,
    progress: Float = 0.5f,
    totalHours: Long = 10,
): ContinueListeningItem.Ready =
    ContinueListeningItem.Ready(
        bookId = id,
        book =
            ContinueListeningBook(
                bookId = id,
                title = title,
                authorNames = "A. Writer",
                coverPath = null,
                coverHash = null,
                progress = progress,
                currentPositionMs = (totalHours * HOUR_MS * progress).toLong(),
                totalDurationMs = totalHours * HOUR_MS,
                lastPlayedAt = "2026-08-24T09:00:00Z",
            ),
    )

/** A Home state that is loaded and quiet — nothing syncing, nothing scanning. */
internal fun readyHome(
    userName: String = "Simon",
    timeGreeting: String = "Good afternoon",
    continueListening: List<ContinueListeningItem> = emptyList(),
    myShelves: List<Shelf> = emptyList(),
    isSyncing: Boolean = false,
    isBuildingInitialLibrary: Boolean = false,
    scanProgress: ScanProgressState? = null,
): HomeUiState.Ready =
    HomeUiState.Ready(
        userName = userName,
        timeGreeting = timeGreeting,
        continueListening = continueListening,
        myShelves = myShelves,
        isSyncing = isSyncing,
        isBuildingInitialLibrary = isBuildingInitialLibrary,
        scanProgress = scanProgress,
    )

/** A shelf with enough shape to prove Home does NOT render one. */
internal fun shelf(name: String): Shelf =
    Shelf(
        id = ShelfId("shelf-$name"),
        name = name,
        description = null,
        isPrivate = false,
        ownerId = "user-1",
        ownerDisplayName = "Simon",
        bookCount = 12,
        totalDurationSeconds = 0,
        createdAtMs = 0,
        updatedAtMs = 0,
    )

/**
 * Seven day buckets, today first — the order the repository emits, NOT the order they are drawn.
 * [todaySeconds] is deliberately the smallest value so a spec can tell which bar is today by its
 * height as well as by its class.
 */
internal fun weekStats(
    todaySeconds: Long = 60,
    peakSeconds: Long = 3_600,
    currentStreakDays: Int = 5,
    longestStreakDays: Int = 14,
    topGenres: List<GenreShare> = emptyList(),
): HomeStatsUiState.Data =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = todaySeconds + peakSeconds,
        currentStreakDays = currentStreakDays,
        longestStreakDays = longestStreakDays,
        dailyBuckets =
            listOf(DayBucket(0, todaySeconds), DayBucket(1, peakSeconds)) +
                (2..6).map { DayBucket(it, 0) },
        topGenres = topGenres,
    )

/** A live scan, mid-analysis. */
internal fun scanning(
    books: Int = 40,
    booksTotal: Int = 100,
): ScanProgressState =
    ScanProgressState(
        phase = "analyzing",
        current = books,
        total = booksTotal,
        added = 3,
        updated = 1,
        removed = 0,
        books = books,
        booksTotal = booksTotal,
    )
