package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.isFailure
import com.calypsan.listenup.client.core.isSuccess
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.embeddedmeta.format.mp4.Mp4Parser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files

class EmbeddedMetadataParserTest :
    FunSpec({
        val parser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser(), Mp4Parser()),
            )

        test("parses MP3 file end-to-end") {
            runTest {
                val tmp =
                    writeTempAudio(
                        "test",
                        ".mp3",
                        buildMp3File {
                            id3v2 { textFrame("TIT2", "Title") }
                            mpegFrames(durationSeconds = 60)
                        },
                    )
                try {
                    val result = parser.parse(Path(tmp.toAbsolutePath().toString()))
                    result.isSuccess() shouldBe true
                    val parsed = (result as AppResult.Success).data
                    parsed.format shouldBe AudioFormat.Mp3
                    parsed.tags.title shouldBe "Title"
                } finally {
                    Files.delete(tmp)
                }
            }
        }

        test("returns UnsupportedFormat with format=Flac when detector recognises but no parser registered") {
            runTest {
                val flacMagic = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(12)
                val tmp = writeTempAudio("test", ".flac", flacMagic)
                try {
                    val result = parser.parse(Path(tmp.toAbsolutePath().toString()))
                    result.isFailure() shouldBe true
                    val err = (result as AppResult.Failure).error
                    err.shouldBeInstanceOf<AudioMetadataError.UnsupportedFormat>()
                    err.format shouldBe AudioFormat.Flac
                } finally {
                    Files.delete(tmp)
                }
            }
        }

        test("returns UnsupportedFormat with format=null for unrecognised non-audio bytes") {
            runTest {
                val tmp = writeTempAudio("test", ".bin", "RIFF    WAVEdata".toByteArray() + ByteArray(16))
                try {
                    val result = parser.parse(Path(tmp.toAbsolutePath().toString()))
                    result.isFailure() shouldBe true
                    val err = (result as AppResult.Failure).error
                    err.shouldBeInstanceOf<AudioMetadataError.UnsupportedFormat>()
                    err.format shouldBe null
                } finally {
                    Files.delete(tmp)
                }
            }
        }

        test("returns IoError for missing path") {
            runTest {
                val result = parser.parse(Path("/nonexistent/path/to/audio.mp3"))
                result.isFailure() shouldBe true
                val err = (result as AppResult.Failure).error
                err.shouldBeInstanceOf<AudioMetadataError.IoError>()
            }
        }
    })

private fun writeTempAudio(
    prefix: String,
    suffix: String,
    bytes: ByteArray,
): java.nio.file.Path {
    val tmp = Files.createTempFile(prefix, suffix)
    Files.write(tmp, bytes)
    return tmp
}
