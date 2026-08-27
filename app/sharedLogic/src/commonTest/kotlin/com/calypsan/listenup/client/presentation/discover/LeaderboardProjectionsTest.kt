package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun entry(
    name: String = "Simon",
    totalSeconds: Long = 0,
    booksFinished: Int = 0,
    currentStreakDays: Int = 0,
    longestStreakDays: Int = 0,
) = LeaderboardEntry(
    rank = 1,
    userId = "u1",
    displayName = name,
    totalSeconds = totalSeconds,
    booksFinished = booksFinished,
    currentStreakDays = currentStreakDays,
    longestStreakDays = longestStreakDays,
)

/**
 * What a leaderboard tab means, pinned once for every client.
 *
 * Both rules used to live in `private fun`s inside the Compose board, where the browser could not
 * reach them — and the streak rule in particular is the kind a second implementation gets subtly
 * and invisibly wrong.
 */
class LeaderboardProjectionsTest :
    FunSpec({

        test("each tab picks its own ranking out of the one snapshot") {
            val snapshot =
                LeaderboardSnapshot(
                    time = listOf(entry("Time")),
                    books = listOf(entry("Books")),
                    streak = listOf(entry("Streak")),
                )

            leaderboardEntries(snapshot, LeaderboardCategory.Time).single().displayName shouldBe "Time"
            leaderboardEntries(snapshot, LeaderboardCategory.Books).single().displayName shouldBe "Books"
            leaderboardEntries(snapshot, LeaderboardCategory.Streak).single().displayName shouldBe "Streak"
        }

        test("the streak tab ranks the longest run, not the current one") {
            // The whole point of the board. Someone who managed 90 days last winter outranks
            // someone on day 3 today, and reading `currentStreakDays` here would silently invert it.
            val hero = entry(currentStreakDays = 3, longestStreakDays = 90)

            leaderboardLabel(hero, LeaderboardCategory.Streak) shouldBe "90 days"
        }

        test("time reads in the app's own compact duration vocabulary") {
            leaderboardLabel(entry(totalSeconds = 7_500), LeaderboardCategory.Time) shouldBe "2h 5m"
            leaderboardLabel(entry(totalSeconds = 2_700), LeaderboardCategory.Time) shouldBe "45m"
            leaderboardLabel(entry(totalSeconds = 7_200), LeaderboardCategory.Time) shouldBe "2h"
        }

        test("someone who has listened for no time at all still gets a readable stat") {
            // A board row with a blank stat reads as broken. "0m" reads as a starting line.
            leaderboardLabel(entry(totalSeconds = 0), LeaderboardCategory.Time) shouldBe "0m"
            leaderboardLabel(entry(totalSeconds = 30), LeaderboardCategory.Time) shouldBe "0m"
        }

        test("books are counted plainly") {
            leaderboardLabel(entry(booksFinished = 12), LeaderboardCategory.Books) shouldBe "12 books"
        }

        test("one of something is not plural") {
            // The board is the app's most public surface — it has other people's names on it. A row
            // reading "1 books" is the sort of thing that makes the whole screen look unfinished.
            leaderboardLabel(entry(booksFinished = 1), LeaderboardCategory.Books) shouldBe "1 book"
            leaderboardLabel(entry(longestStreakDays = 1), LeaderboardCategory.Streak) shouldBe "1 day"
        }

        test("zero of something stays plural, as English wants") {
            leaderboardLabel(entry(booksFinished = 0), LeaderboardCategory.Books) shouldBe "0 books"
            leaderboardLabel(entry(longestStreakDays = 0), LeaderboardCategory.Streak) shouldBe "0 days"
        }
    })
