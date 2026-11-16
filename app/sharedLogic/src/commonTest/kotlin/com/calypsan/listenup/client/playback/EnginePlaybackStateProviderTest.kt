package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.test.fake.FakePlayerEngine
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class EnginePlaybackStateProviderTest :
    FunSpec({

        test("the current book is the one the engine holds") {
            val engine = FakePlayerEngine()
            val provider = EnginePlaybackStateProvider(engine)

            provider.currentBookId.value.shouldBeNull()
            engine.loadedBookId.value = BookId("book-1")
            provider.currentBookId.value shouldBe BookId("book-1")
        }

        test("clearing playback stops the engine, which lets go of its book") {
            val engine = FakePlayerEngine()
            engine.loadedBookId.value = BookId("book-1")
            val provider = EnginePlaybackStateProvider(engine)

            provider.clearPlayback()

            engine.calls shouldContainExactly listOf("stop")
            provider.currentBookId.value.shouldBeNull()
        }
    })
