package com.calypsan.listenup.client.features.share

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Share never does nothing: with a link it opens the share sheet, without one it says why. */
@RunWith(RobolectricTestRunner::class)
class ShareBookLinkTest {
    @Test
    fun withALinkTheBookIsShared() =
        runTest {
            val shared = mutableListOf<Pair<String, String>>()
            var explained = 0

            shareBookLink(
                url = "https://listenup.example/b/1",
                title = "The Way of Kings",
                share = { text, url -> shared += text to url },
                onNoLink = { explained++ },
            )

            shared shouldBe
                listOf("Check out The Way of Kings on ListenUp!\nhttps://listenup.example/b/1" to "https://listenup.example/b/1")
            explained shouldBe 0
        }

    @Test
    fun withoutALinkShareSaysSo() =
        runTest {
            var shares = 0
            var explained = 0

            shareBookLink(
                url = null,
                title = "The Way of Kings",
                share = { _, _ -> shares++ },
                onNoLink = { explained++ },
            )

            shares shouldBe 0
            explained shouldBe 1
        }
}
