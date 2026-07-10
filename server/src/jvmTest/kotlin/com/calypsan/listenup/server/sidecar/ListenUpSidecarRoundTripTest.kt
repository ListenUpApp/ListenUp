package com.calypsan.listenup.server.sidecar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Round-trip tests for [ListenUpSidecar] — the `listenup.json` disk model (spec §5).
 * The field names ARE the disk format; these tests pin the exact shape and the lenient
 * forward-compat behaviour a hand-edited or older/newer-server file must survive.
 */
class ListenUpSidecarRoundTripTest :
    FunSpec({

        test("a fully-populated instance survives serialize then parse unchanged") {
            val sidecar =
                ListenUpSidecar(
                    schemaVersion = 1,
                    identity =
                        SidecarIdentity(
                            asin = "B0ABCDEFG",
                            chapterFingerprint = "deadbeef",
                            titleAuthor = "The Way of Kings / Brandon Sanderson",
                        ),
                    metadata =
                        SidecarCuratedMetadata(
                            title = "The Way of Kings",
                            subtitle = "Book One of the Stormlight Archive",
                            description = "A war that could destroy the world.",
                            contributors =
                                listOf(
                                    SidecarContributor(name = "Brandon Sanderson", role = "author"),
                                    SidecarContributor(name = "Michael Kramer", role = "narrator"),
                                ),
                            series = listOf(SidecarSeriesEntry(name = "The Stormlight Archive", sequence = "1")),
                            genres = listOf("Fantasy", "Epic Fantasy"),
                            tags = listOf("favorites"),
                        ),
                    userEditedFields = listOf("TITLE", "DESCRIPTION"),
                    chapters =
                        SidecarChapters(
                            source = "USER",
                            entries =
                                listOf(
                                    SidecarChapter(title = "Prelude", startMs = 0L),
                                    SidecarChapter(title = "Prologue", startMs = 120_000L),
                                ),
                        ),
                )

            val bytes = SidecarJson.serialize(sidecar)
            val parsed = SidecarJson.parseOrNull(bytes)

            parsed shouldBe sidecar
        }

        test("an unknown field is ignored — forward compatibility with a newer writer") {
            val json =
                """
                {
                  "schemaVersion": 1,
                  "identity": {"titleAuthor": "Book / Author"},
                  "metadata": {"title": "Book"},
                  "somethingFromTheFuture": {"nested": true}
                }
                """.trimIndent()

            val parsed = SidecarJson.parseOrNull(json.encodeToByteArray())

            parsed.shouldNotBeNull()
            parsed.metadata.title shouldBe "Book"
        }

        test("schemaVersion 2 still parses structurally — the reader decides policy, not the parser") {
            val json =
                """
                {
                  "schemaVersion": 2,
                  "identity": {"titleAuthor": "Book / Author"},
                  "metadata": {"title": "Book"}
                }
                """.trimIndent()

            val parsed = SidecarJson.parseOrNull(json.encodeToByteArray())

            parsed.shouldNotBeNull()
            parsed.schemaVersion shouldBe 2
        }

        test("garbage bytes parse to null, never throw") {
            val parsed = SidecarJson.parseOrNull("not json at all {{{".encodeToByteArray())

            parsed.shouldBeNull()
        }

        test("readingOrders carries opaque stub objects untouched") {
            val json =
                """
                {
                  "schemaVersion": 1,
                  "identity": {"titleAuthor": "Book / Author"},
                  "metadata": {"title": "Book"},
                  "readingOrders": [{"name": "Chronological", "custom": 42}]
                }
                """.trimIndent()

            val parsed = SidecarJson.parseOrNull(json.encodeToByteArray())

            parsed.shouldNotBeNull()
            parsed.readingOrders.size shouldBe 1
        }
    })
