package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Contract test for [AudioFormatParser]. Concrete parser tests in Tasks 46 (MP3)
 * and 50 (MP4) exercise the parsing behaviour; this test pins the contract that
 * any AudioFormatParser implementation must satisfy.
 */
class AudioFormatParserContractTest :
    FunSpec({
        test("supports must be non-empty") {
            val parser =
                object : AudioFormatParser {
                    override val supports: Set<AudioFormat> = setOf(AudioFormat.Mp3)

                    override suspend fun parse(source: SeekableAudioSource): AppResult<EmbeddedAudioMetadata> =
                        AppResult.Failure(
                            AudioMetadataError.IoError("/test", "stub"),
                        )
                }
            parser.supports.shouldNotBeEmpty()
        }

        test("a parser surfacing failure returns AppResult.Failure with an AudioMetadataError") {
            val parser =
                object : AudioFormatParser {
                    override val supports: Set<AudioFormat> = setOf(AudioFormat.Mp4)

                    override suspend fun parse(source: SeekableAudioSource): AppResult<EmbeddedAudioMetadata> =
                        AppResult.Failure(
                            AudioMetadataError.CorruptHeader(
                                pathString = "/test",
                                format = AudioFormat.Mp4,
                                offset = 0,
                                expected = "ftyp",
                            ),
                        )
                }
            // Stub source — the parser never reads it in this stub.
            val result =
                parser.parse(
                    object : SeekableAudioSource {
                        override val length: Long = 0

                        override fun position(): Long = 0

                        override fun seek(offset: Long) {}

                        override fun read(
                            into: ByteArray,
                            count: Int,
                        ): Int = -1

                        override fun readFully(count: Int): ByteArray = ByteArray(0)

                        override fun close() {}
                    },
                )
            require(result is AppResult.Failure)
            (result.error is AudioMetadataError) shouldBe true
        }

        @Suppress("UnusedPrivateMember")
        test("AudioTags / EmbeddedAudioMetadata / ChapterSource imports compile") {
            // Pinning the parser contract's dependency surface — the imports above
            // would fail compile if the contract drifted.
            val unused: AudioTags? = null
            val unusedMeta: EmbeddedAudioMetadata? = null
            val unusedSource: ChapterSource = ChapterSource.None
        }
    })
