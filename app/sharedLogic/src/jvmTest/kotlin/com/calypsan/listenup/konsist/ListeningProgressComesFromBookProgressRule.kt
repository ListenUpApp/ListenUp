package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * A book's percent, time left and progress fraction come from `BookProgress` (`contract/.../domain/BookProgress.kt`),
 * never from arithmetic a screen does itself.
 *
 * Before `BookProgress`, Library and Home rounded the percent, Series and the readers list truncated it, iOS rebuilt
 * the time left from a rounded fraction and Book Detail ignored the end credits, so one book read 39% on one screen
 * and 40% on the next. Every client file now reads `fraction`, `percent` and `remainingMs` off the one rule; this
 * rule stops a screen working them out again.
 *
 * It flags, in client production code (Kotlin in `:app:sharedLogic`, `:app:sharedUI`, `:app:webApp`, and the iOS
 * app's Swift):
 * - a position divided by a duration (`positionMs.toFloat() / book.duration`): a hand-rolled fraction;
 * - a duration minus a position (`book.duration - positionMs`): a hand-rolled time left;
 * - a duration scaled by one minus a fraction (`duration * (1 - progress)`): a time left rebuilt from a bar;
 * - a progress or fraction times 100 (`(fraction * 100).roundToInt()`): a percent rounded a second way;
 * - a percent divided by 100 (`progressPercent / 100f`): a fraction rebuilt from a rounded percent;
 * - the web bar's own rounding, `percentOf(fraction)`, called outside the bar.
 *
 * The player's progress is a different quantity — the position in the current chapter, against the chapter's length
 * — so the player and playback directories are out of scope ([isPlayerCode]). A percent that is not a book's
 * listening progress at all (a scan, a download, a share of ratings) is exempt by path in
 * [NOT_LISTENING_PROGRESS_ALLOWLIST], with its reason. Comments are skipped, so KDoc may still describe the arithmetic.
 */
class ListeningProgressComesFromBookProgressRule :
    FunSpec({
        val root = File(Konsist.projectRootPath)

        test("no client file computes a book's percent, time left or fraction itself") {
            val files = clientProductionSources(root)
            assertScopeNotEmpty(
                files,
                expectedMin = 1_000,
                why = "every client production Kotlin and Swift file: the screens whose progress labels this pins",
            )

            files
                .filterNot { (path, _) -> isPlayerCode(path) }
                .filterNot { (path, _) -> NOT_LISTENING_PROGRESS_ALLOWLIST.keys.any(path::endsWith) }
                .flatMap { (path, text) -> handRolledProgressLines(text).map { line -> "$path:$line" } }
                .shouldBeEmpty()
        }

        test("every allowlisted file still exists, so the allowlist cannot outlive what it excuses") {
            val paths = clientProductionSources(root).map { it.first }
            NOT_LISTENING_PROGRESS_ALLOWLIST.keys
                .filterNot { suffix ->
                    paths.any { it.endsWith(suffix) }
                }.shouldBeEmpty()
        }
    })

/**
 * Files whose percent is not a book's listening progress, keyed by path suffix, valued by the reason. A new entry
 * needs the same: say what the number is, if it is not where a listener is in a book.
 */
internal val NOT_LISTENING_PROGRESS_ALLOWLIST: Map<String, String> =
    mapOf(
        "features/setup/scan/LibraryScanScreen.kt" to "how far a library scan has got",
        "features/home/components/GenreBreakdownBars.kt" to "a genre's share of listening time",
        "features/bookdetail/DownloadButton.kt" to "how much of a download has arrived",
        "features/bookdetail/components/DownloadAction.kt" to "how much of a download has arrived",
        "features/bookdetail/components/RatingBreakdownSheet.kt" to "a star level's share of the ratings",
        "features/chaptereditor/ChapterTimeline.kt" to "where a chapter mark sits on the editor's timeline",
        "web/design/Progress.kt" to "the bar's own announcement for work with no percent of its own; books pass theirs",
        "web/design/FramedImg.kt" to "an image frame's geometry",
        "Features/LibrarySetup/LibraryScanView.swift" to "how far a library scan has got",
        "Features/Admin/Import/ImportFlowObserver.swift" to "how far an import has got",
        "Features/DocumentReader/NowPlayingStrip.swift" to "the player's chapter time left, beside a document",
    )

/** The player's chapter progress: a different quantity, with its own rule (`presentation/player/PlayerProgress.kt`). */
internal fun isPlayerCode(path: String): Boolean = PLAYER_DIRECTORIES.any { it in path }

private val PLAYER_DIRECTORIES = listOf("/presentation/player/", "/playback/", "/nowplaying/", "/Features/Player/")

private val HAND_ROLLED_PROGRESS =
    listOf(
        // A position over a duration: `positionMs.toFloat() / book.duration`, `Float(positionMs) / Float(duration)`.
        """[pP]osition\w*(?:\.\w+\(\))?\)?\s*/\s*(?:\w+\()?[\w.]*[dD]uration""",
        // A duration minus a position: `book.duration - position.positionMs`, `totalDurationMs - currentPositionMs`.
        """[dD]uration\w*\)?\s*-\s*(?:\w+\()?[\w.]*[pP]osition""",
        // A duration times one minus a fraction: `Double(book.duration) * Double(1 - progress)`.
        """[dD]uration[^\n]*\*[^\n]*\(\s*1(?:\.0)?f?\s*-\s*[\w.]*(?:progress|fraction)\s*\)""",
        // A fraction times 100: `(it.fraction * 100).roundToInt()`, `(progress * PERCENT).toInt()`.
        """\b(?:progress|fraction)(?:\.coerceIn\([^)]*\))?\s*\*\s*(?:100(?:\.0)?f?|PERCENT\w*)\b""",
        // A percent over 100: `book.progressPercent / PERCENT.toFloat()`.
        """[pP]ercent\w*\s*/\s*(?:100(?:\.0)?f?|PERCENT\w*)""",
        // The web bar's fallback rounding, outside the bar: `"${percentOf(fraction)}%"`.
        """\bpercentOf\(""",
    ).map(::Regex)

/** 1-based line of every hand-rolled book-progress computation in [text], ignoring comments. Distinct, in order. */
internal fun handRolledProgressLines(text: String): List<Int> {
    val code = blankComments(text)

    fun lineOf(offset: Int) = code.substring(0, offset).count { char -> char == '\n' } + 1
    return HAND_ROLLED_PROGRESS
        .flatMap { pattern -> pattern.findAll(code).map { match -> lineOf(match.range.first) } }
        .distinct()
        .sorted()
}

private val CLIENT_KOTLIN_ROOTS = listOf("app/sharedLogic/src/", "app/sharedUI/src/", "app/webApp/src/")
private const val SWIFT_ROOT = "app/iosApp/ListenUp"

/** Every client production Kotlin and Swift file under [root], as (repo-relative path, text). */
private fun clientProductionSources(root: File): List<Pair<String, String>> {
    val kotlin =
        walkKotlinSources(root)
            .filter { source -> CLIENT_KOTLIN_ROOTS.any(source.relativePath::startsWith) }
            .filterNot { source ->
                source.relativePath
                    .substringAfter("/src/")
                    .substringBefore('/')
                    .contains("test", ignoreCase = true)
            }.map { it.relativePath to it.text }
    val swift =
        File(root, SWIFT_ROOT)
            .walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "swift" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toList()
    return kotlin + swift
}
