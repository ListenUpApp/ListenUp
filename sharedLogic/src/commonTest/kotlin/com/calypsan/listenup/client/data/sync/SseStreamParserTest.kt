package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SseStreamParserTest :
    FunSpec({

        test("single frame with id/event/data parses to ParsedSseFrame") {
            val raw =
                """
                id: 42
                event: tags
                data: {"foo":"bar"}

                """.trimIndent()
            val frames = parseSseStream(raw.lineSequence())
            frames shouldContainExactly
                listOf(
                    ParsedSseFrame(id = 42L, event = "tags", data = """{"foo":"bar"}"""),
                )
        }

        test("multi-line data: lines accumulate with newline separators") {
            val raw =
                """
                id: 1
                event: tags
                data: {
                data: "foo":"bar"
                data: }

                """.trimIndent()
            val frames = parseSseStream(raw.lineSequence())
            frames shouldContainExactly
                listOf(
                    ParsedSseFrame(id = 1L, event = "tags", data = "{\n\"foo\":\"bar\"\n}"),
                )
        }

        test("comment lines (lines starting with ':') are ignored") {
            val raw =
                """
                : keepalive
                id: 7
                event: tags
                data: x

                """.trimIndent()
            val frames = parseSseStream(raw.lineSequence())
            frames.size shouldBe 1
            frames[0].id shouldBe 7L
        }

        test("blank line terminates a frame; multiple frames parse in order") {
            val raw =
                """
                id: 1
                event: tags
                data: a

                id: 2
                event: tags
                data: b

                """.trimIndent()
            val frames = parseSseStream(raw.lineSequence())
            frames.map { it.id } shouldContainExactly listOf(1L, 2L)
            frames.map { it.data } shouldContainExactly listOf("a", "b")
        }

        test("frame missing id: is allowed (control events have no id)") {
            val raw =
                """
                event: control
                data: {"type":"SyncControl.CursorStale"}

                """.trimIndent()
            val frames = parseSseStream(raw.lineSequence())
            frames.size shouldBe 1
            frames[0].id shouldBe null
            frames[0].event shouldBe "control"
        }

        test("reconnectDelay schedule: 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s") {
            val schedule = (0..7).map { reconnectDelayMillis(attempt = it) }
            schedule shouldContainExactly
                listOf(
                    1_000L,
                    2_000L,
                    4_000L,
                    8_000L,
                    16_000L,
                    32_000L,
                    60_000L,
                    60_000L,
                )
        }
    })
