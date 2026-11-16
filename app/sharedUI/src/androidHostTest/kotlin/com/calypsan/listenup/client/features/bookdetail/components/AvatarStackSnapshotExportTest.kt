package com.calypsan.listenup.client.features.bookdetail.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaderFace
import com.calypsan.listenup.client.presentation.bookdetail.ChapterReaders
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersPlace
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Book Detail's overlapping avatar stacks to a PNG, so tucked initials can be looked at. Off unless
 * LU_SNAPSHOT_DIR names a directory, so it never runs in CI.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class AvatarStackSnapshotExportTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar resolves its collaborators through a composition Koin, which registers globally. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun face(
        name: String,
        isLive: Boolean = false,
    ) = ChapterReaderFace(userId = name.lowercase(), displayName = name, isLive = isLive)

    private fun refs(vararg names: String) = names.map { ReaderRef(userId = it.lowercase(), displayName = it) }

    @Test
    fun export() {
        val dir = System.getenv("LU_SNAPSHOT_DIR")
        assumeTrue("Set LU_SNAPSHOT_DIR to export the avatar stacks", !dir.isNullOrBlank())
        val ring = Color.White
        composeRule.setContent {
            WithAvatarDependencies {
                Column(
                    modifier = Modifier.background(ring).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ChapterReaderStack(ChapterReaders(ReadersPlace.ThisChapter, listOf(face("Maya Wren"))), ring)
                    ChapterReaderStack(
                        ChapterReaders(
                            ReadersPlace.ThisChapter,
                            listOf(face("Maya Wren"), face("Tom Okafor"), face("Walt Moss")),
                        ),
                        ring,
                    )
                    ChapterReaderStack(
                        ChapterReaders(
                            ReadersPlace.ThisChapter,
                            listOf(face("Priya Shah", isLive = true), face("Walt Moss"), face("Tom Okafor")),
                        ),
                        ring,
                    )
                    ChapterReaderStack(
                        ChapterReaders(
                            ReadersPlace.ThisChapter,
                            listOf(face("Walt Moss"), face("Maya Wren"), face("Dan Reyes"), face("Hal Burke"), face("Ann Lee")),
                        ),
                        ring,
                    )
                    HeroSocialLine(
                        summary =
                            ReadersSummary(
                                listening = refs("Dan Reyes", "Hal Burke"),
                                finished = refs("Walt Moss"),
                                avatars = refs("Dan Reyes", "Hal Burke", "Walt Moss"),
                                othersCount = 0,
                            ),
                        ringColor = ring,
                        onOpenReaders = {},
                    )
                }
            }
        }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "android-avatar-stacks.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
