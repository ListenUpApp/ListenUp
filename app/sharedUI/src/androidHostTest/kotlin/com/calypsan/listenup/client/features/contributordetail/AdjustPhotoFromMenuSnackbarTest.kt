package com.calypsan.listenup.client.features.contributordetail

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.image.decodePhotosWithBitmapFactory
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.design.image.pngFile
import com.calypsan.listenup.client.design.image.restoreCoil
import com.calypsan.listenup.client.design.image.startKoinForContributorPhotos
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Save closes the editor and then says "Photo adjusted.". The message has to outlive the editor: launched
 * from the editor's own scope, it was cancelled the moment the editor closed and never appeared.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdjustPhotoFromMenuSnackbarTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun stopKoinAfterPhotos() {
        stopKoin()
        restoreCoil()
    }

    @Test
    fun `Save closes the editor and still says the photo was adjusted`() {
        startKoinForContributorPhotos(pngFile().absolutePath)
        decodePhotosWithBitmapFactory()
        var adjusted: ImageFrame? = null
        compose.setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val screenScope = rememberCoroutineScope()
                var adjusting by remember { mutableStateOf(true) }
                Box {
                    if (adjusting) {
                        AdjustPhotoFromMenu(
                            contributor = Contributor(ContributorId("c1"), "Le Guin", imagePath = "contributors/abc.jpg"),
                            adjustPhoto = { adjusted = it },
                            snackbarHostState = snackbarHostState,
                            snackbarScope = screenScope,
                            onClose = { adjusting = false },
                            onOpenEdit = {},
                        )
                    }
                    SnackbarHost(snackbarHostState)
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Save").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Save").performClick()

        adjusted.shouldNotBeNull()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Photo adjusted.")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
