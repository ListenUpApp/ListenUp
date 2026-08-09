package com.calypsan.listenup.web.features.bookdetail

/** One chapter of the static sample book. Replaced by store data at the wiring step. */
internal class SampleChapter(
    val number: Int,
    val title: String,
    val startSec: Int,
    val durationSec: Int,
) {
    val endSec: Int get() = startSec + durationSec
}

/** Total sample runtime — 9:14:06, agreeing with the Details panel on the overview pane. */
private const val TOTAL_SECONDS = 9 * 3600 + 14 * 60 + 6

private const val CHAPTER_COUNT = 33

/**
 * 33 deterministic chapters whose durations vary but always sum to [TOTAL_SECONDS] — the two
 * panes describing one book must not disagree, even as placeholders.
 */
internal val SAMPLE_CHAPTERS: List<SampleChapter> =
    buildList {
        val durations = IntArray(CHAPTER_COUNT) { index -> BASE_SECONDS + index * VARIATION_STEP % VARIATION_RANGE }
        durations[CHAPTER_COUNT - 1] += TOTAL_SECONDS - durations.sum()
        var start = 0
        durations.forEachIndexed { index, duration ->
            add(
                SampleChapter(
                    number = index + 1,
                    title = "Chapter ${index + 1}",
                    startSec = start,
                    durationSec = duration,
                ),
            )
            start += duration
        }
    }

private const val BASE_SECONDS = 700

private const val VARIATION_STEP = 137

private const val VARIATION_RANGE = 600

/** `h:mm:ss` once hours exist, `m:ss` below that — the clock format the comps use. */
internal fun formatClock(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    val two = { value: Int -> value.toString().padStart(2, '0') }
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "$minutes:${two(seconds)}"
}
