package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

/**
 * Round-trip every metadata DTO and the provider-neutral [MetadataLocale] through
 * [contractJson]. Any drift in field names, polymorphic discriminators, or
 * default-value handling fails here before any pipeline code runs.
 */
class MetadataLookupContractTest :
    FunSpec({

        // ── MetadataLocale ─────────────────────────────────────────────────────

        test("MetadataLocale entries round-trip through JSON") {
            val samples = MetadataLocale.SUPPORTED + MetadataLocale.DEFAULT + MetadataLocale(region = "de", language = "de")
            samples.forEach { locale ->
                roundTrip<MetadataLocale>(locale) shouldBe locale
            }
        }

        test("MetadataLocale serializes its region under the stable key") {
            // @SerialName("region") pins the wire key; a null language is omitted (encodeDefaults = false).
            val json = contractJson.encodeToString(MetadataLocale("uk"))
            json shouldBe "{\"region\":\"uk\"}"
        }

        // ── MetadataContributorRef ─────────────────────────────────────────────

        test("MetadataContributorRef survives round-trip with asin present") {
            val ref = MetadataContributorRef(asin = "B001ASIN01", name = "Brandon Sanderson")
            roundTrip<MetadataContributorRef>(ref) shouldBe ref
        }

        test("MetadataContributorRef survives round-trip with null asin") {
            val ref = MetadataContributorRef(asin = null, name = "Unknown Author")
            roundTrip<MetadataContributorRef>(ref) shouldBe ref
        }

        // ── MetadataSeriesRef ──────────────────────────────────────────────────

        test("MetadataSeriesRef survives round-trip with all fields") {
            val ref = MetadataSeriesRef(asin = "B002SER01", title = "Stormlight Archive", sequence = "1.5")
            roundTrip<MetadataSeriesRef>(ref) shouldBe ref
        }

        test("MetadataSeriesRef survives round-trip with null fields") {
            val ref = MetadataSeriesRef(asin = null, title = "Standalone", sequence = null)
            roundTrip<MetadataSeriesRef>(ref) shouldBe ref
        }

        // ── MetadataBook ───────────────────────────────────────────────────────

        test("MetadataBook survives round-trip with all fields populated") {
            val book =
                MetadataBook(
                    asin = "B0015T963C",
                    title = "The Way of Kings",
                    subtitle = "Book One of the Stormlight Archive",
                    description = "A long epic fantasy novel.",
                    publisher = "Macmillan Audio",
                    releaseDate = "2010-08-31",
                    runtimeMinutes = 2230,
                    language = "en-US",
                    authors = listOf(MetadataContributorRef(asin = "B001A1", name = "Brandon Sanderson")),
                    narrators =
                        listOf(
                            MetadataContributorRef(asin = "B001N1", name = "Michael Kramer"),
                            MetadataContributorRef(asin = "B001N2", name = "Kate Reading"),
                        ),
                    series = listOf(MetadataSeriesRef(asin = "B001S1", title = "Stormlight Archive", sequence = "1")),
                    genres = listOf("Fantasy", "Epic Fantasy"),
                    moods = listOf("Adventurous", "Epic"),
                    tags = listOf("Chosen One", "Magic System"),
                    coverUrl = "https://m.media-amazon.com/images/I/example.jpg",
                    coverUrlMaxSize = "https://is1-ssl.mzstatic.com/image/thumb/example/7000x7000bb.jpg",
                )
            roundTrip<MetadataBook>(book) shouldBe book
        }

        test("MetadataBook survives round-trip with all nullable fields null") {
            val minimal =
                MetadataBook(
                    asin = "B000MIN01",
                    title = "Untitled",
                    subtitle = null,
                    description = null,
                    publisher = null,
                    releaseDate = null,
                    runtimeMinutes = null,
                    language = null,
                    authors = emptyList(),
                    narrators = emptyList(),
                    series = emptyList(),
                    genres = emptyList(),
                    coverUrl = null,
                    coverUrlMaxSize = null,
                )
            roundTrip<MetadataBook>(minimal) shouldBe minimal
        }

        // ── MetadataSearchResults ──────────────────────────────────────────────

        test("MetadataSearchResults survives round-trip with multiple hits") {
            val results =
                MetadataSearchResults(
                    hits =
                        listOf(
                            MetadataBook("B001", "Book A", null, null, null, null, 600, null, emptyList(), emptyList(), emptyList(), emptyList(), coverUrl = null, coverUrlMaxSize = null),
                            MetadataBook("B002", "Book B", "Sub B", null, "Pub B", "2020-01-01", 720, "en-US", emptyList(), emptyList(), emptyList(), emptyList(), coverUrl = "http://cover.jpg", coverUrlMaxSize = null),
                        ),
                )
            roundTrip<MetadataSearchResults>(results) shouldBe results
        }

        test("MetadataSearchResults survives round-trip with empty hits") {
            val empty = MetadataSearchResults(hits = emptyList())
            roundTrip<MetadataSearchResults>(empty) shouldBe empty
        }

        // ── MetadataChapter / MetadataChapters ─────────────────────────────────

        test("MetadataChapter survives round-trip") {
            val chapter = MetadataChapter(title = "Prologue", startMs = 0L, lengthMs = 120_000L)
            roundTrip<MetadataChapter>(chapter) shouldBe chapter
        }

        test("MetadataChapters survives round-trip with multiple chapters") {
            val chapters =
                MetadataChapters(
                    chapters =
                        listOf(
                            MetadataChapter("Prologue", 0L, 120_000L),
                            MetadataChapter("Chapter 1", 120_000L, 1_800_000L),
                            MetadataChapter("Epilogue", 1_920_000L, 300_000L),
                        ),
                )
            roundTrip<MetadataChapters>(chapters) shouldBe chapters
        }

        test("MetadataChapters survives round-trip with empty chapter list") {
            val empty = MetadataChapters(chapters = emptyList())
            roundTrip<MetadataChapters>(empty) shouldBe empty
        }

        // ── MetadataContributorProfile ─────────────────────────────────────────

        test("MetadataContributorProfile survives round-trip with all fields") {
            val profile =
                MetadataContributorProfile(
                    asin = "B001PROF01",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    description = "American fantasy author.",
                    imageUrl = "https://m.media-amazon.com/images/I/profile.jpg",
                    birthDate = "1975-12-19",
                    deathDate = null,
                    website = "https://brandonsanderson.com",
                )
            roundTrip<MetadataContributorProfile>(profile) shouldBe profile
        }

        test("MetadataContributorProfile survives round-trip with all optional fields null") {
            val minimal =
                MetadataContributorProfile(
                    asin = "B001PROF02",
                    name = "Unknown Narrator",
                    sortName = null,
                    description = null,
                    imageUrl = null,
                    birthDate = null,
                    deathDate = null,
                    website = null,
                )
            roundTrip<MetadataContributorProfile>(minimal) shouldBe minimal
        }

        // ── MetadataContributorHit ─────────────────────────────────────────────

        test("MetadataContributorHit survives round-trip") {
            val hit = MetadataContributorHit(asin = "B001HIT01", name = "Patrick Rothfuss")
            roundTrip<MetadataContributorHit>(hit) shouldBe hit
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
