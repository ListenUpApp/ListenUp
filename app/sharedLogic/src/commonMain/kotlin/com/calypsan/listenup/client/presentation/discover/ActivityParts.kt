package com.calypsan.listenup.client.presentation.discover

/**
 * One activity rendered as a sentence, in three pieces a client can style independently.
 *
 * Split rather than pre-joined because every client colours the middle differently: the book or
 * shelf name is the brand-coloured, tappable part, the predicate is body text, and the suffix
 * trails plainly. Handing back a finished string would force each client to parse it apart again.
 *
 * @property predicate What they did — "finished", "listened to 30 minutes of".
 * @property highlight The book or shelf name, or null when the event names nothing.
 * @property suffix Trailing plain text, e.g. " by Frank Herbert". Empty when there is none.
 */
data class ActivityParts(
    val predicate: String,
    val highlight: String?,
    val suffix: String,
)

/**
 * Turn an activity into the sentence every client reads out.
 *
 * This is user-facing copy, not layout, which is why it is here rather than in a UI module: it
 * lived in a `private fun` inside the Compose feed until the browser needed the same rows, and a
 * second copy would have drifted the first time either wording was touched.
 *
 * An unrecognised [ActivityUiModel.type] gets a cheerful placeholder rather than an empty row or a
 * throw — the server may ship a new kind of activity before this client learns to name it, and
 * "someone did something" is still truer than showing nothing.
 */
fun activityParts(activity: ActivityUiModel): ActivityParts {
    val authorSuffix = formatActivityAuthor(activity.bookAuthorName)?.let { " by $it" }.orEmpty()
    return when (activity.type) {
        "started_book" -> {
            val predicate = if (activity.isReread) "started re-reading" else "started reading"
            ActivityParts(predicate, activity.bookTitle ?: A_BOOK, authorSuffix)
        }

        "finished_book" -> {
            ActivityParts("finished", activity.bookTitle ?: A_BOOK, authorSuffix)
        }

        "listening_session" -> {
            ActivityParts(
                "listened to ${formatDurationMinutes(activity.durationMs)} of",
                activity.bookTitle ?: A_BOOK,
                "",
            )
        }

        "streak_milestone" -> {
            ActivityParts("reached a ${activity.milestoneValue}-day listening streak", null, "")
        }

        "listening_milestone" -> {
            ActivityParts("listened for ${plural(activity.milestoneValue, "hour")} total", null, "")
        }

        "shelf_created" -> {
            ActivityParts("created the shelf", activity.shelfName ?: A_SHELF, "")
        }

        "user_joined" -> {
            ActivityParts("joined the server", null, "")
        }

        else -> {
            ActivityParts("did something awesome", null, "")
        }
    }
}

/**
 * An author line for a feed row: the first name plus "et al." when a book has several.
 *
 * A feed row is one line. Spelling out five collaborators would let a single row push everything
 * else off the screen, which is the opposite of what a feed is for.
 */
private fun formatActivityAuthor(authorName: String?): String? {
    if (authorName.isNullOrBlank()) return null
    val authors = authorName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return if (authors.size <= 1) authorName else "${authors.first()} et al."
}

/**
 * A listening duration in long-form words: "30 seconds", "1 hour 30 minutes".
 *
 * Deliberately not `DurationFormatter`, whose forms are all compact ("1h 30m") for spaces where a
 * number has to fit. This one sits mid-sentence, where the compact form would read as a stutter.
 */
private fun formatDurationMinutes(durationMs: Long): String {
    val totalSeconds = (durationMs / MILLIS_PER_SECOND).toInt()
    val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR

    return when {
        totalMinutes == 0 -> plural(totalSeconds, "second")
        hours == 0 -> plural(minutes, "minute")
        minutes == 0 -> plural(hours, "hour")
        else -> "${plural(hours, "hour")} ${plural(minutes, "minute")}"
    }
}

private const val A_BOOK = "a book"

private const val A_SHELF = "a shelf"

private const val MILLIS_PER_SECOND = 1_000

private const val SECONDS_PER_MINUTE = 60

private const val MINUTES_PER_HOUR = 60
