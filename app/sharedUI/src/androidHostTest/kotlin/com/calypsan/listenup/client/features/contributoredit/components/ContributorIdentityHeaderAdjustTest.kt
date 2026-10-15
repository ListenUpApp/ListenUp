package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.image.pngFile
import com.calypsan.listenup.client.design.image.startKoinForContributorPhotos
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner

/** Contributor edit offers Adjust photo under the photo, and only when there is a photo to adjust. */
@RunWith(RobolectricTestRunner::class)
class ContributorIdentityHeaderAdjustTest {
    @get:Rule
    val compose = createComposeRule()

    private var adjusted = false

    @Before
    fun startKoinForPhotos() {
        startKoinForContributorPhotos(pngFile().absolutePath)
    }

    @After
    fun stopKoinAfterPhotos() {
        stopKoin()
    }

    private fun show(
        imagePath: String?,
        stagingImagePath: String? = null,
    ) = compose.setContent {
        MaterialTheme {
            ContributorIdentityHeader(
                contributorId = "c1",
                imagePath = imagePath,
                stagingImagePath = stagingImagePath,
                frame = null,
                name = "Ursula K. Le Guin",
                colorScheme = rememberContributorColorScheme("c1"),
                isUploadingImage = false,
                onNameChange = {},
                onAvatarClick = {},
                onAdjustPhoto = if (imagePath != null || stagingImagePath != null) ({ adjusted = true }) else null,
                onBackClick = {},
            )
        }
    }

    @Test
    fun `with a photo, Adjust photo opens the editor`() {
        show(imagePath = "contributors/a.jpg")
        compose.onNodeWithText("Adjust photo").performClick()
        adjusted shouldBe true
    }

    @Test
    fun `with no photo there is nothing to adjust`() {
        show(imagePath = null)
        compose.onNodeWithText("Adjust photo").assertDoesNotExist()
    }
}
