package com.calypsan.listenup.client.features.contributoredit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.image.decodePhotosWithBitmapFactory
import com.calypsan.listenup.client.design.image.pngBytes
import com.calypsan.listenup.client.design.image.pngFile
import com.calypsan.listenup.client.design.image.restoreCoil
import com.calypsan.listenup.client.design.image.startKoinForContributorPhotos
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Contributor edit frames a photo inside the form (Q2): Done stages, it never saves. A new pick is staged
 * with its frame; adjusting a staged pick re-stages it; adjusting the saved photo stages just the frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributorPhotoEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<ContributorEditUiEvent>()
    private var choseAnother = false
    private var dismissed = false

    @Before
    fun startKoinForPhotos() {
        startKoinForContributorPhotos(pngFile().absolutePath)
        decodePhotosWithBitmapFactory()
    }

    @After
    fun stopKoinAfterPhotos() {
        stopKoin()
        restoreCoil()
    }

    private fun show(
        editing: ContributorFrameEdit,
        state: ContributorEditUiState = ContributorEditUiState(contributorId = "c1", imagePath = "contributors/a.jpg"),
    ) = compose.setContent {
        MaterialTheme {
            ContributorPhotoEditor(
                editing = editing,
                state = state,
                onEvent = { events += it },
                onChooseAnother = { choseAnother = true },
                onDismiss = { dismissed = true },
            )
        }
    }

    private fun pressDone() {
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Done").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun `Done on a new pick stages it with its frame`() {
        val bytes = pngBytes()
        show(ContributorFrameEdit.NewPick(bytes, "portrait.png"))
        pressDone()
        val upload = events.single().shouldBeInstanceOf<ContributorEditUiEvent.UploadImage>()
        upload.imageData shouldBe bytes
        upload.filename shouldBe "portrait.png"
        upload.frame.shouldNotBeNull()
        dismissed shouldBe true
    }

    @Test
    fun `adjusting a staged pick stages it again with the new frame`() {
        val staged = pngBytes()
        show(
            ContributorFrameEdit.Existing,
            ContributorEditUiState(contributorId = "c1", pendingImageData = staged, pendingImageFilename = "staged.png"),
        )
        pressDone()
        val upload = events.single().shouldBeInstanceOf<ContributorEditUiEvent.UploadImage>()
        upload.imageData shouldBe staged
        upload.filename shouldBe "staged.png"
    }

    @Test
    fun `adjusting the saved photo stages only its frame`() {
        show(ContributorFrameEdit.Existing)
        pressDone()
        events.single().shouldBeInstanceOf<ContributorEditUiEvent.StageFrame>()
    }

    @Test
    fun `a pick that cannot be opened offers another`() {
        show(ContributorFrameEdit.Undecodable)
        compose.onNodeWithText("Choose another").performClick()
        choseAnother shouldBe true
        events shouldBe emptyList()
    }

    @Test
    fun `the editor previews the detail scallop and a list circle`() {
        show(ContributorFrameEdit.NewPick(pngBytes(), "portrait.png"))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Detail and lists")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
