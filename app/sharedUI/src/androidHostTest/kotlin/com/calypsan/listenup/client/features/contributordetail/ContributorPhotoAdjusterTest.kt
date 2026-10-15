package com.calypsan.listenup.client.features.contributordetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.image.decodePhotosWithBitmapFactory
import com.calypsan.listenup.client.design.image.pngFile
import com.calypsan.listenup.client.design.image.restoreCoil
import com.calypsan.listenup.client.design.image.startKoinForContributorPhotos
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Adjust photo from the contributor's menu commits on Save (Q2). When the existing photo will not open,
 * "Choose another" goes to Contributor edit, where a new photo can be picked (R6).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributorPhotoAdjusterTest {
    @get:Rule
    val compose = createComposeRule()

    private var saved: ImageFrame? = null
    private var openedEdit = false

    @After
    fun stopKoinAfterPhotos() {
        stopKoin()
        restoreCoil()
    }

    private fun show(photoPath: String) {
        startKoinForContributorPhotos(photoPath)
        decodePhotosWithBitmapFactory()
        compose.setContent {
            MaterialTheme {
                ContributorPhotoAdjuster(
                    contributorId = "c1",
                    imagePath = "contributors/abc.jpg",
                    frame = null,
                    onSave = { saved = it },
                    onDismiss = {},
                    onOpenEdit = { openedEdit = true },
                )
            }
        }
    }

    @Test
    fun `from the menu the editor saves`() {
        show(pngFile().absolutePath)
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Save").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        saved.shouldNotBeNull()
    }

    @Test
    fun `a photo that will not open sends Choose another to Contributor edit`() {
        show("/nonexistent/contributor.jpg")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Choose another")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Choose another").performClick()
        openedEdit shouldBe true
    }
}
