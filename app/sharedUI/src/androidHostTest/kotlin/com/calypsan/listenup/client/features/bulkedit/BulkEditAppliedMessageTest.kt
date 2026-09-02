package com.calypsan.listenup.client.features.bulkedit

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [bulkEditAppliedMessage], the confirmation a landed bulk edit is announced with.
 *
 * It resolves compose-resources strings through `getString`, which needs a real Android resource
 * environment — hence JUnit4 + Robolectric, following
 * [com.calypsan.listenup.client.presentation.error.AppErrorLocalizationTest].
 */
@RunWith(RobolectricTestRunner::class)
class BulkEditAppliedMessageTest {
    @Test
    fun `one book is announced in the singular`() =
        runTest {
            bulkEditAppliedMessage(1) shouldBe "1 book updated"
        }

    @Test
    fun `several books are counted`() =
        runTest {
            bulkEditAppliedMessage(8) shouldBe "8 books updated"
        }
}
