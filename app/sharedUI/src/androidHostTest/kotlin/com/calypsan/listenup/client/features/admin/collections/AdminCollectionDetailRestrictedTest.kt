package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.design.components.LocalRestrictedBookIds
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionBookItem
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A collection's cover tiles wear the lock on a restricted book, and only on it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class AdminCollectionDetailRestrictedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a cover tile locks the restricted book only`() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRestrictedBookIds provides setOf("restricted")) {
                    DetailBody(
                        state = READY,
                        isWide = false,
                        innerPadding = PaddingValues(),
                        onBackClick = {},
                        onNameChange = {},
                        onSaveClick = {},
                        onRemoveBookClick = {},
                        onAddBooksClick = {},
                        onAddMemberClick = {},
                        onRemoveMemberClick = {},
                    )
                }
            }
        }
        composeRule.onAllNodesWithContentDescription(RESTRICTED_A11Y, useUnmergedTree = true).assertCountEquals(1)
    }

    private companion object {
        const val RESTRICTED_A11Y = "In a collection, so only people it is shared with can see it."

        fun book(id: String) =
            CollectionBookItem(
                id = id,
                title = "Book $id",
                author = null,
                coverPath = "/tmp/cover-$id.webp",
                durationMs = 3_600_000L,
            )

        val READY =
            AdminCollectionDetailUiState.Ready(
                collection =
                    Collection(
                        id = "c1",
                        name = "Kids",
                        ownerId = "root",
                        isInbox = false,
                        isSystem = false,
                        bookCount = 2,
                        callerPermission = SharePermission.Write,
                        isOwner = true,
                    ),
                editedName = "Kids",
                books = listOf(book("restricted"), book("open")),
            )
    }
}
