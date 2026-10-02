package com.calypsan.listenup.client.presentation.metadata

import com.calypsan.listenup.api.dto.MatchProvenance
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.BookField
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private fun match(
    genres: List<String> = emptyList(),
    moods: List<String> = emptyList(),
    genreSources: Map<String, String> = emptyMap(),
) = MetadataBook(
    asin = "B08G9RZBTT",
    title = "Project Hail Mary",
    subtitle = null,
    description = null,
    publisher = null,
    releaseDate = null,
    runtimeMinutes = null,
    language = null,
    authors = emptyList(),
    narrators = emptyList(),
    series = emptyList(),
    genres = genres,
    moods = moods,
    coverUrl = null,
    coverUrlMaxSize = null,
    matchProvenance = MatchProvenance(genreSources = genreSources),
)

private fun ready(
    preview: MetadataBook,
    fallbackSources: Map<BookField, String> = emptyMap(),
    genreCandidates: List<String> = preview.genres,
    moodCandidates: List<String> = preview.moods,
) = PreviewLoadState.Ready(
    preview = preview,
    selections = MetadataSelections(),
    coverEntries = emptyList(),
    selectedCoverUrl = null,
    isApplying = false,
    applyError = null,
    previewNotFound = false,
    chapterSuggestion = ChapterSuggestion.Unavailable,
    genreCandidates = genreCandidates,
    moodCandidates = moodCandidates,
    tagCandidates = emptyList(),
    fallbackSources = fallbackSources,
)

/** The bridge-safe per-genre and per-mood source lookups every platform's preview reads (#1542). */
class PreviewProvenanceTest :
    FunSpec({
        test("a genre Hardcover added says so; the match's own genre says nothing") {
            val state = ready(match(genres = listOf("Science Fiction", "Space Opera"), genreSources = mapOf("Space Opera" to "Hardcover")))

            state.genreSourceFor("Space Opera") shouldBe "Hardcover"
            state.genreSourceFor("Science Fiction").shouldBeNull()
        }

        test("when the genres field itself fell back, every proposed genre carries that source") {
            val state =
                ready(
                    match(genres = listOf("Science Fiction"), genreSources = mapOf("Science Fiction" to "Hardcover")),
                    fallbackSources = mapOf(BookField.GENRES to "Hardcover"),
                )

            state.genreSourceFor("Science Fiction") shouldBe "Hardcover"
        }

        test("a genre or mood the book already had, not proposed by the match, claims no source") {
            val state =
                ready(
                    match(genres = listOf("Science Fiction"), moods = listOf("Hopeful")),
                    fallbackSources = mapOf(BookField.GENRES to "Audnexus", BookField.MOODS to "Hardcover"),
                    genreCandidates = listOf("Literary", "Science Fiction"),
                    moodCandidates = listOf("Tense", "Hopeful"),
                )

            state.genreSourceFor("Literary").shouldBeNull()
            state.moodSourceFor("Tense").shouldBeNull()
            state.moodSourceFor("Hopeful") shouldBe "Hardcover"
        }

        test("no provenance means no labels") {
            val state = ready(match(genres = listOf("Science Fiction"), moods = listOf("Hopeful")))

            state.genreSourceFor("Science Fiction").shouldBeNull()
            state.moodSourceFor("Hopeful").shouldBeNull()
        }
    })
