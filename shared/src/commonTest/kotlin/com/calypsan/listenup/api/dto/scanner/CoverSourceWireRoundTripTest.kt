package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that both [CoverSource] variants round-trip through the canonical
 * [contractJson] using the polymorphic `CoverSource.serializer()`.
 *
 * `CoverSource` is sealed — kotlinx.serialization auto-resolves subtypes
 * from the sealed hierarchy, so no explicit module registration is required.
 */
class CoverSourceWireRoundTripTest :
    FunSpec({
        val json = contractJson

        test("Filesystem round-trips") {
            val cover: CoverSource =
                CoverSource.Filesystem(
                    file =
                        FileEntry(
                            relPath = "book/cover.jpg",
                            name = "cover.jpg",
                            ext = "jpg",
                            size = 4096,
                            mtimeMs = 1_700_000_000_000,
                            inode = 42L,
                            fileType = FileType.IMAGE,
                        ),
                )
            val decoded = json.decodeFromString(CoverSource.serializer(), json.encodeToString(CoverSource.serializer(), cover))
            decoded shouldBe cover
        }

        test("Embedded round-trips") {
            val cover: CoverSource =
                CoverSource.Embedded(
                    artwork = EmbeddedArtwork(mime = "image/jpeg", bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
                )
            val decoded = json.decodeFromString(CoverSource.serializer(), json.encodeToString(CoverSource.serializer(), cover))
            decoded shouldBe cover
        }
    })
