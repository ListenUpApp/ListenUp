package com.calypsan.listenup.domain.embeddedmeta

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AudioStreamInfoTest :
    FunSpec({
        test("AudioStreamInfo round-trips through JSON") {
            val v =
                AudioStreamInfo(
                    codec = "ac4",
                    codecProfile = null,
                    spatial = "atmos",
                    bitrate = 320000,
                    sampleRate = 48000,
                    channels = 2,
                )
            contractJson.decodeFromString<AudioStreamInfo>(contractJson.encodeToString(v)) shouldBe v
        }

        test("EmbeddedAudioMetadata defaults audioStream to null") {
            val meta =
                EmbeddedAudioMetadata(
                    format = AudioFormat.Mp4,
                    durationMs = 0,
                    tags = emptyTagsForTest(),
                    chapters = emptyList(),
                    chaptersSource = ChapterSource.None,
                    artwork = null,
                )
            meta.audioStream shouldBe null
        }
    })

private fun emptyTagsForTest() =
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
        custom = emptyMap(),
    )
