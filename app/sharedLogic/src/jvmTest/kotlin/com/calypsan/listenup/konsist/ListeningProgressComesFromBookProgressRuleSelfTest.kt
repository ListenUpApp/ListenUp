package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [handRolledProgressLines], so the rule is known to fire before it is trusted. */
class ListeningProgressComesFromBookProgressRuleSelfTest :
    FunSpec({
        test("every shape the old screens used is reported by line") {
            val source =
                """
                |val progress = (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
                |val left = (detail.duration - positionMs).coerceAtLeast(0L)
                |val percent = (status.fraction * 100).roundToInt()
                |Text("${'$'}{(progress * 100).toInt()}%")
                |val bar = book.progressPercent / PERCENT.toFloat()
                |let remaining = Int64(Double(book.duration) * Double(1 - progress))
                |Text("\(Int((progress * 100).rounded()))%")
                |val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
                |get() = (totalDurationMs - currentPositionMs).coerceAtLeast(0)
                |Text("${'$'}{percentOf(fraction)}%")
                |progress = { reader.progressPct.coerceIn(0, 100) / 100f },
                |ProgressBar(value = pct / PERCENT, decorative = true)
                """.trimMargin()
            handRolledProgressLines(source) shouldBe (1..12).toList()
        }

        test("a raw finished-flag read is reported by line; the flag handed to the one rule is not") {
            val source =
                """
                |.filter { it.isFinished }
                |val bookProgress = BookProgress.of(position?.positionMs, detail.duration, position?.isFinished == true)
                |if (position.isFinished) position.finishedAtMs else null
                |// a comment about it.isFinished
                |val finishedMap = positions.filterValues { it.isFinished }
                """.trimMargin()
            rawFinishedFlagLines(source) shouldBe listOf(1, 3, 5)
        }

        test("the finished-flag check covers presentation code only") {
            isPresentationCode(
                "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/presentation/library/LibraryViewModel.kt",
            ) shouldBe true
            isPresentationCode(
                "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/playback/PlaybackPreparer.kt",
            ) shouldBe false
        }

        test("reading the one rule's numbers is not reported") {
            val source =
                """
                |val progress = BookProgress.of(position?.positionMs, detail.duration, position?.isFinished == true)
                |Text(stringResource(Res.string.library_card_progress_a11y, status.percent))
                |LinearProgressIndicator(progress = { status.fraction })
                |Text("\(progress.percent)%")
                |val left = inProgress?.remainingMs
                |ProgressBar(value = book.progress, percent = book.progressPercent)
                """.trimMargin()
            handRolledProgressLines(source) shouldBe emptyList()
        }

        test("arithmetic quoted in a comment is not reported") {
            val source =
                """
                |// was (position.positionMs.toFloat() / book.duration)
                |/* (fraction * 100).roundToInt() */
                """.trimMargin()
            handRolledProgressLines(source) shouldBe emptyList()
        }

        test("the player's chapter progress is out of scope by directory") {
            isPlayerCode(
                "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/presentation/player/PlayerProgress.kt",
            ) shouldBe
                true
            isPlayerCode("app/iosApp/ListenUp/Features/Player/MiniPlayerBar.swift") shouldBe true
            isPlayerCode("app/webApp/src/jsMain/kotlin/com/calypsan/listenup/web/features/nowplaying/TransportBar.kt") shouldBe true
            isPlayerCode("app/sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/features/library/BookCard.kt") shouldBe false
        }
    })
