package com.calypsan.listenup.client.design.icons

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.features.chaptereditor.ChapterEditRow
import com.calypsan.listenup.client.features.chaptereditor.ChapterRowMenuActions
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every glyph in [ListenUpIcons] must put ink on the canvas inside its 24 dp bounds -- a wrong viewBox transform in
 * the generator draws nothing (or draws off-canvas), and this is where that shows.
 *
 * Set `LISTENUP_ICON_SHOTS_DIR` (and optionally `LISTENUP_SHOT_LABEL`) to also write PNGs of the icon strip and of a
 * chapter row there, for eyeballing a change.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, qualifiers = "w412dp-h915dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class ListenUpIconsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val everyIcon =
        with(ListenUpIcons) {
            listOf(
                Add,
                AddFilled,
                ArrowBack,
                ArrowBackFilled,
                Check,
                CheckFilled,
                Close,
                CloseFilled,
                Delete,
                Edit,
                EditFilled,
                Info,
                Lock,
                LockFilled,
                Person,
                PersonFilled,
                Search,
                SearchFilled,
            )
        }

    @Test
    fun `every glyph draws ink inside its 24dp bounds`() {
        composeRule.setContent {
            MaterialTheme {
                Row {
                    everyIcon.forEachIndexed { index, icon ->
                        Box(Modifier.testTag("icon-$index").background(Color.White)) {
                            Icon(icon, contentDescription = null, tint = Color.Black)
                        }
                    }
                }
            }
        }
        everyIcon.forEachIndexed { index, icon ->
            val pixels = composeRule.onNodeWithTag("icon-$index").captureToImage().toPixelMap()
            val ink = (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y -> pixels[x, y] != Color.White } }
            withClue("${icon.name} drew no ink") { ink shouldBeGreaterThan 0 }
        }
    }

    @Test
    fun `screenshots, when asked for`() {
        val dir = System.getenv("LISTENUP_ICON_SHOTS_DIR")?.let(::File) ?: return
        val label = System.getenv("LISTENUP_SHOT_LABEL") ?: "after"
        dir.mkdirs()

        composeRule.setContent {
            MaterialTheme {
                Row(Modifier.testTag("row").background(Color.White).padding(8.dp)) {
                    chapterRowOrStrip(label)
                }
            }
        }
        composeRule.onNodeWithTag("row").captureToImage().writePng(File(dir, "$label.png"))
    }

    @androidx.compose.runtime.Composable
    private fun chapterRowOrStrip(label: String) {
        if (label == "strip") {
            Column {
                everyIcon.chunked(STRIP_COLUMNS).forEach { rowOfIcons ->
                    Row {
                        rowOfIcons.forEach { glyph ->
                            Icon(
                                glyph,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.padding(4.dp).size(32.dp),
                            )
                        }
                    }
                }
            }
        } else {
            ChapterEditRow(
                chapter = Chapter(id = "c1", title = "The Spear That Would Not Break", duration = 806_000L, startTime = 148_328_400L),
                number = 213,
                isSelected = false,
                isPlaying = false,
                onSelect = {},
                onNudge = {},
                onSnapToPlayhead = {},
                onToggleLock = {},
                menu = ChapterRowMenuActions({}, {}, null, {}),
                isLocked = true,
                onEditTime = {},
            )
        }
    }

    private companion object {
        const val STRIP_COLUMNS = 9
    }

    private fun ImageBitmap.writePng(file: File) {
        file.outputStream().use { asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
