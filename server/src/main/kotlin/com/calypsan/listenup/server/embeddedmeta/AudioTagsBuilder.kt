package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.domain.embeddedmeta.AudioTags

/**
 * Mutable accumulator the format readers (MP3 `Id3v2Reader`, MP4 `IlstReader`)
 * fill frame-by-frame, then seal via [build]. Single shared definition — the
 * two readers previously each carried an identical copy.
 */
internal class AudioTagsBuilder {
    var title: String? = null
    var subtitle: String? = null
    val authors: MutableList<String> = mutableListOf()
    val narrators: MutableList<String> = mutableListOf()
    var seriesName: String? = null
    var seriesPart: String? = null
    var grouping: String? = null
    val genres: MutableList<String> = mutableListOf()
    var description: String? = null
    var publisher: String? = null
    var publishedYear: Int? = null
    var asin: String? = null
    var isbn: String? = null
    var language: String? = null
    var trackNumber: Int? = null
    var discNumber: Int? = null
    var titleSort: String? = null
    var authorsSort: String? = null
    val custom: MutableMap<String, String> = linkedMapOf()

    fun build(): AudioTags {
        val series =
            when {
                seriesName != null -> SeriesTagParser.zipSeries(seriesName, seriesPart)
                grouping != null -> SeriesTagParser.parsePacked(grouping!!)
                else -> emptyList()
            }
        return AudioTags(
            title = title,
            subtitle = subtitle,
            authors = authors.toList(),
            narrators = narrators.toList(),
            series = series,
            genres = genres.toList(),
            description = description,
            publisher = publisher,
            publishedYear = publishedYear,
            asin = asin,
            isbn = isbn,
            language = language,
            trackNumber = trackNumber,
            discNumber = discNumber,
            titleSort = titleSort,
            authorsSort = authorsSort,
            custom = custom.toMap(),
        )
    }
}

internal fun emptyAudioTags(): AudioTags =
    AudioTags(
        title = null,
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList(),
        genres = emptyList(),
        description = null,
        publisher = null,
        publishedYear = null,
        asin = null,
        isbn = null,
        language = null,
        trackNumber = null,
        discNumber = null,
        titleSort = null,
        authorsSort = null,
        custom = emptyMap(),
    )
