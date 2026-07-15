package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.scanner.sidecar.xml.firstText
import com.calypsan.listenup.server.scanner.sidecar.xml.getElementsByTagName
import com.calypsan.listenup.server.scanner.sidecar.xml.parseXml
import kotlin.math.roundToLong

/**
 * Reconstructs chapters from OverDrive/Libby `TXXX:"OverDrive MediaMarkers"` frames.
 *
 * OverDrive audiobooks carry no embedded chapter atoms (Nero `chpl`, ID3 `CHAP`);
 * instead each MP3 stores an XML marker block in an ID3 `TXXX` frame whose
 * description is `"OverDrive MediaMarkers"`:
 *
 * ```xml
 * <Markers>
 *   <Marker><Name>Chapter 1</Name><Time>0:00.000</Time></Marker>
 *   <Marker><Name>Chapter 2</Name><Time>15:51.000</Time></Marker>
 * </Markers>
 * ```
 *
 * `Time` is relative to the start of *its own* track (`H:MM:SS.mmm`, `MM:SS.mmm`,
 * or `SS.mmm`), so a multi-file book is stitched into one list with cumulative
 * track-duration offsets. Port of Audiobookshelf's `parseOverdriveMediaMarkers.js`
 * (itself after benonymity's OverdriveChapterizer).
 *
 * All-or-nothing: every track must carry a parseable marker frame, else `null`
 * so the caller falls back to per-track synthesis. "Weird" OverDrive sub-chapters
 * — names containing `(` immediately followed by a digit, or the word "continued"
 * — are dropped, matching ABS.
 *
 * One deliberate divergence from ABS: a well-formed marker frame that contains *no*
 * markers (or only weird ones) counts as "present" — the reconstruction proceeds
 * from the other tracks rather than discarding every chapter. ABS instead falls
 * back to synthesis whenever any track's cleaned marker list is empty. Keeping the
 * real chapters we do have is the more content-preserving choice.
 */
internal object OverdriveChapters {
    /** The ID3 `TXXX` frame description under which OverDrive stores its marker XML. */
    const val MARKERS_TAG_KEY: String = "OverDrive MediaMarkers"

    private val WEIRD_CHAPTER = Regex("""[(]\d|[cC]ontinued""")

    /**
     * Stitches every track's OverDrive markers into one cumulative chapter list.
     *
     * @param tracks ordered track list (stable sort already applied)
     * @param metadataOf resolves a track's parsed metadata — the marker XML lives in
     *   `tags.custom["OverDrive MediaMarkers"]` and `durationMs` drives the cumulative
     *   offset. Returning `null` for any track aborts the whole reconstruction.
     * @return the stitched chapters, or `null` when any track lacks a parseable marker
     *   frame or no chapters survive.
     */
    fun parse(
        tracks: List<TrackEntry>,
        metadataOf: (TrackEntry) -> EmbeddedAudioMetadata?,
    ): List<Chapter>? {
        if (tracks.isEmpty()) return null
        var cumulativeMs = 0L
        val starts = mutableListOf<Pair<String, Long>>()
        for (track in tracks) {
            val meta = metadataOf(track) ?: return null
            val markerXml = meta.tags.custom[MARKERS_TAG_KEY] ?: return null
            val markers = parseMarkers(markerXml) ?: return null
            markers.forEach { (name, relativeMs) -> starts += name to (cumulativeMs + relativeMs) }
            cumulativeMs += meta.durationMs
        }
        if (starts.isEmpty()) return null
        // End of each chapter is the next chapter's start; the last runs to the book's end.
        val stitched =
            starts
                .mapIndexed { i, (name, startMs) ->
                    Chapter(
                        index = i + 1,
                        title = name,
                        startMs = startMs,
                        endMs = if (i < starts.lastIndex) starts[i + 1].second else cumulativeMs,
                    )
                }.dropGhostChapters()
        if (stitched.isEmpty()) return null
        // Re-assert the runs-to-book-end rule in case a trailing sub-0.1s marker was dropped.
        return stitched.mapIndexed { i, chapter ->
            if (i == stitched.lastIndex) chapter.copy(endMs = cumulativeMs) else chapter
        }
    }

    /**
     * One track's `<Markers>` XML → (name, track-relative-ms) pairs, weird sub-chapters
     * filtered. `null` only when the XML is unparseable; an all-filtered block yields an
     * empty list (the track is still "present" for the all-or-nothing guard).
     */
    private fun parseMarkers(xml: String): List<Pair<String, Long>>? =
        try {
            parseXml(xml)
                .getElementsByTagName("Marker")
                .mapNotNull { marker ->
                    val name =
                        marker.firstText("Name")?.takeUnless { WEIRD_CHAPTER.containsMatchIn(it) }
                            ?: return@mapNotNull null
                    val time = marker.firstText("Time") ?: return@mapNotNull null
                    name to parseTimeToMs(time)
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** Parses `H:MM:SS.mmm` / `MM:SS.mmm` / `SS.mmm` into milliseconds. Throws on a malformed component. */
    private fun parseTimeToMs(time: String): Long {
        var seconds = 0.0
        var unit = 1.0
        for (part in time.trim().split(':').asReversed()) {
            seconds += part.toDouble() * unit
            unit *= 60.0
        }
        return (seconds * 1000.0).roundToLong()
    }
}
