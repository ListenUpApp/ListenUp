package com.calypsan.listenup.client.presentation.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun activity(
    type: String,
    bookTitle: String? = "Dune",
    bookAuthorName: String? = "Frank Herbert",
    isReread: Boolean = false,
    durationMs: Long = 0L,
    milestoneValue: Int = 0,
    shelfName: String? = null,
) = ActivityUiModel(
    id = "a1",
    userId = "u1",
    type = type,
    occurredAt = 0L,
    userDisplayName = "Simon",
    bookId = "b1",
    bookTitle = bookTitle,
    bookAuthorName = bookAuthorName,
    bookCoverPath = null,
    isReread = isReread,
    durationMs = durationMs,
    milestoneValue = milestoneValue,
    milestoneUnit = null,
    shelfId = null,
    shelfName = shelfName,
)

/**
 * The activity sentence, which is user-facing copy rather than layout.
 *
 * It lived in a `private fun` inside a Compose-Android file until the browser needed the same
 * feed. Restating seven predicates, a re-read variant and an "et al." rule in a second language
 * would have drifted the moment either side was reworded — so it is one function, pinned here.
 */
class ActivityProjectionsTest :
    FunSpec({

        test("a started book distinguishes a first read from a re-read") {
            activityParts(activity("started_book")).predicate shouldBe "started reading"
            activityParts(activity("started_book", isReread = true)).predicate shouldBe "started re-reading"
        }

        test("the book is the highlight, and its author trails as plain text") {
            val parts = activityParts(activity("finished_book"))

            parts.predicate shouldBe "finished"
            parts.highlight shouldBe "Dune"
            parts.suffix shouldBe " by Frank Herbert"
        }

        test("a book the feed cannot name still reads as a sentence") {
            // The row exists because something happened; dropping it because the title did not
            // arrive would hide the event rather than report it plainly.
            activityParts(activity("finished_book", bookTitle = null)).highlight shouldBe "a book"
        }

        test("several authors collapse to the first, so one row cannot eat the feed") {
            activityParts(activity("started_book", bookAuthorName = "Ann Leckie, Ada Palmer")).suffix shouldBe
                " by Ann Leckie et al."
            activityParts(activity("started_book", bookAuthorName = null)).suffix shouldBe ""
            activityParts(activity("started_book", bookAuthorName = "  ")).suffix shouldBe ""
        }

        test("a listening session says how long, in words, and counts singulars properly") {
            activityParts(activity("listening_session", durationMs = 30_000)).predicate shouldBe
                "listened to 30 seconds of"
            activityParts(activity("listening_session", durationMs = 60_000)).predicate shouldBe
                "listened to 1 minute of"
            activityParts(activity("listening_session", durationMs = 3_600_000)).predicate shouldBe
                "listened to 1 hour of"
            activityParts(activity("listening_session", durationMs = 5_400_000)).predicate shouldBe
                "listened to 1 hour 30 minutes of"
            activityParts(activity("listening_session", durationMs = 7_260_000)).predicate shouldBe
                "listened to 2 hours 1 minute of"
        }

        test("milestones and joins carry no highlight, because there is nothing to link to") {
            activityParts(activity("streak_milestone", milestoneValue = 7)).let {
                it.predicate shouldBe "reached a 7-day listening streak"
                it.highlight shouldBe null
            }
            activityParts(activity("listening_milestone", milestoneValue = 100)).predicate shouldBe
                "listened for 100 hours total"
            // Someone's very first milestone is the one most worth getting right.
            activityParts(activity("listening_milestone", milestoneValue = 1)).predicate shouldBe
                "listened for 1 hour total"
            activityParts(activity("user_joined")).predicate shouldBe "joined the server"
        }

        test("a created shelf highlights the shelf, not the book") {
            activityParts(activity("shelf_created", shelfName = "Comfort reads")).let {
                it.predicate shouldBe "created the shelf"
                it.highlight shouldBe "Comfort reads"
            }
            activityParts(activity("shelf_created", shelfName = null)).highlight shouldBe "a shelf"
        }

        test("an activity type this client has never heard of still renders as a row") {
            // The server may ship a type before the client learns it. A blank row, or a crash, is
            // worse than a cheerful placeholder that still names who did it.
            activityParts(activity("teleported")).let {
                it.predicate shouldBe "did something awesome"
                it.highlight shouldBe null
            }
        }
    })
