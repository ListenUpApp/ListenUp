package com.calypsan.listenup.client.features.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.client.design.components.LocalRestrictedBookIds
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A search row's cover wears the compact lock for a restricted book. */
@RunWith(RobolectricTestRunner::class)
class BookResultRowRestrictedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a restricted hit's cover carries the lock`() {
        setContent(restricted = setOf("b1"))
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an unrestricted hit carries none`() {
        setContent(restricted = emptySet())
        composeRule.onNodeWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun setContent(restricted: Set<String>) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRestrictedBookIds provides restricted) {
                    BookResultRow(hit = HIT, query = "dune", onClick = {})
                }
            }
        }
    }

    private companion object {
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."
    }
}

/** A local cover path keeps BookCoverImage off the Koin-backed async fallback. */
private val HIT =
    SearchHit(
        id = "b1",
        type = SearchHitType.BOOK,
        name = "The Ministry of Time",
        author = "Kaliane Bradley",
        duration = 42_720_000L,
        coverPath = "/tmp/cover-b1.webp",
        isHeld = false,
    )
