package com.calypsan.listenup.client.features.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.components.CONTRIBUTOR_PHOTO_FALLBACK_TAG
import com.calypsan.listenup.client.design.components.CONTRIBUTOR_PHOTO_TAG
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * A contributor search hit shows the contributor's framed photo in its circle, as the Contributors
 * list does (board `no-permission`, "List row · circle 48"), and keeps today's person icon when the
 * contributor has no photo.
 */
@RunWith(RobolectricTestRunner::class)
class PersonResultRowPhotoTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** ContributorCoverImage resolves its bytes through the GLOBAL Koin context. */
    @Before
    fun startKoinForPhotos() {
        startKoin {
            modules(
                module {
                    single<ImageRepository> {
                        mock(MockMode.autoUnit) {
                            every { getContributorImagePath(any()) } returns "/tmp/contributor-c1.jpg"
                            every { contributorImageExists(any()) } returns true
                        }
                    }
                    single<ServerConfig> { mock(MockMode.autoUnit) }
                    single<AuthSession> { mock(MockMode.autoUnit) }
                },
            )
        }
    }

    @After
    fun stopKoinAfterPhotos() {
        stopKoin()
    }

    private fun render(imagePath: String?) {
        composeRule.setContent {
            MaterialTheme {
                PersonResultRow(
                    hit =
                        SearchHit(
                            id = "c1",
                            type = SearchHitType.CONTRIBUTOR,
                            name = "Ursula K. Le Guin",
                            imagePath = imagePath,
                            imageFrame = ImageFrame(0.5, 0.3, 1.6).takeIf { imagePath != null },
                        ),
                    query = "",
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun `a contributor with a photo shows it`() {
        render(imagePath = "contributors/abc.jpg")

        composeRule.onNodeWithTag(CONTRIBUTOR_PHOTO_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a contributor without a photo keeps the person icon`() {
        render(imagePath = null)

        composeRule.onNodeWithTag(CONTRIBUTOR_PHOTO_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(CONTRIBUTOR_PHOTO_FALLBACK_TAG, useUnmergedTree = true).assertExists()
    }
}
