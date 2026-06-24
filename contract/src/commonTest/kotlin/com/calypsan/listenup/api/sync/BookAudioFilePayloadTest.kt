package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookAudioFilePayloadTest :
    FunSpec({
        test("round-trips the new audio-stream fields") {
            val v =
                BookAudioFilePayload(
                    id = "af1",
                    index = 0,
                    filename = "book.m4b",
                    format = "m4b",
                    codec = "ac4",
                    duration = 1000,
                    size = 2048,
                    codecProfile = null,
                    spatial = "atmos",
                    bitrate = 320000,
                    sampleRate = 48000,
                    channels = 2,
                )
            contractJson.decodeFromString<BookAudioFilePayload>(contractJson.encodeToString(v)) shouldBe v
        }

        test("new fields default to null for back-compat with older payloads") {
            val json = """{"id":"a","index":0,"filename":"f","format":"mp3","codec":"mp3","duration":1,"size":2}"""
            val v = contractJson.decodeFromString<BookAudioFilePayload>(json)
            v.codecProfile shouldBe null
            v.spatial shouldBe null
            v.bitrate shouldBe null
            v.sampleRate shouldBe null
            v.channels shouldBe null
        }
    })
