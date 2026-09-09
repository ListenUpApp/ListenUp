package com.calypsan.listenup.api.metadata

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json

/**
 * The additive-wire guarantee on the **enum-typed MAP KEY** axis — the one axis no `Json` setting
 * reaches.
 *
 * A `Map<Enum, _>` encodes to a JSON object whose KEYS are the enum names, and on decode each key
 * goes through the enum's serializer, where an unrecognised name throws. `ignoreUnknownKeys` governs
 * unknown *properties of a class*; `coerceInputValues` governs a *property with a default*. Neither
 * touches a map key. So one new [BookField] on a newer server made every book payload carrying
 * provenance for it undecodable on every older client — freezing the hottest sync domain there is —
 * and left a downgraded server unable to read the column it had just written.
 *
 * Three comments in this repo asserted the opposite. [FieldProvenanceMapSerializer] is the thing
 * that actually makes the claim true.
 */
private fun payload(fieldProvenance: Map<BookField, FieldProvenance>) =
    BookSyncPayload(
        id = "book-1",
        libraryId = LibraryId("lib-1"),
        folderId = FolderId("folder-1"),
        title = "The Way of Kings",
        sortTitle = null,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 1_000L,
        cover = null,
        rootRelPath = "Sanderson/The Way of Kings",
        inode = null,
        scannedAt = 0L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        fieldProvenance = fieldProvenance,
        revision = 1L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

class FieldProvenanceMapSerializerTest :
    FunSpec({
        val known = mapOf(BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 111))

        // A payload from a hypothetical newer server: provenance for a field this build has never
        // heard of, riding alongside one it has.
        val futureWire =
            contractJson.encodeToString(BookSyncPayload.serializer(), payload(known)).replace(
                """"fieldProvenance":{"TITLE":""",
                """"fieldProvenance":{"QUANTUM_BLURB":{"kind":"ENRICHMENT","at":222},"TITLE":""",
            )

        test("a provenance key this build does not know is dropped rather than throwing") {
            shouldNotThrowAny {
                contractJson.decodeFromString(BookSyncPayload.serializer(), futureWire)
            }

            contractJson
                .decodeFromString(BookSyncPayload.serializer(), futureWire)
                .fieldProvenance shouldContainExactly known
        }

        test("a map of only known keys round-trips unchanged") {
            val value =
                payload(
                    mapOf(
                        BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 111),
                        BookField.DESCRIPTION to FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 222),
                    ),
                )

            contractJson.decodeFromString(
                BookSyncPayload.serializer(),
                contractJson.encodeToString(BookSyncPayload.serializer(), value),
            ) shouldBe value
        }

        test("the encoded key stays the plain enum name, so the stored/wire format is unchanged") {
            val wire = contractJson.encodeToString(BookSyncPayload.serializer(), payload(known))

            wire.contains(""""fieldProvenance":{"TITLE":{"kind":"USER","at":111}}""") shouldBe true
        }

        // Control probe: the plain enum-keyed MapSerializer — the codec all three sites used, and
        // the one whose comments claimed `ignoreUnknownKeys` covered this — still throws.
        test("WITHOUT the tolerant serializer the same object throws — ignoreUnknownKeys never reached map keys") {
            val plainJson = Json { ignoreUnknownKeys = true }
            val plainSerializer = MapSerializer(BookField.serializer(), FieldProvenance.serializer())

            shouldThrow<SerializationException> {
                plainJson.decodeFromString(
                    plainSerializer,
                    """{"QUANTUM_BLURB":{"kind":"ENRICHMENT","at":222},"TITLE":{"kind":"USER","at":111}}""",
                )
            }
        }
    })
