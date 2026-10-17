package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.ShelfCover
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A shelf's mosaic is always four tiles: its covers first, in shelf order, then the placeholder tone. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShelfCoverMosaicTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourTilesAlwaysCoversFirstThenPlaceholders() {
        composeRule.setContent {
            MaterialTheme {
                ShelfCoverMosaic(
                    listOf(ShelfCover("b1", "/tmp/1.webp", null), ShelfCover("b2", "/tmp/2.webp", null)),
                    Modifier.size(156.dp),
                )
            }
        }
        composeRule.onAllNodesWithTag("mosaic-cover", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithTag("mosaic-empty", useUnmergedTree = true).assertCountEquals(2)
    }
}
