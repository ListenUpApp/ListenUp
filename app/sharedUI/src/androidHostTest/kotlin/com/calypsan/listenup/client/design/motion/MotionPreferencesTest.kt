package com.calypsan.listenup.client.design.motion

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins where Android's motion preferences come from: "Remove animations" is the animator duration
 * scale at zero, and TalkBack is touch exploration.
 */
@RunWith(RobolectricTestRunner::class)
class MotionPreferencesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `motion is allowed with the default animator scale and no screen reader`() {
        val (reduceMotion, touchExploration) = providedPreferences()
        reduceMotion shouldBe false
        touchExploration shouldBe false
    }

    @Test
    fun `removed animations reduce motion`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        providedPreferences().first shouldBe true
    }

    @Test
    fun `TalkBack's touch exploration is reported`() {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibilityManager).setTouchExplorationEnabled(true)
        providedPreferences().second shouldBe true
    }

    private fun providedPreferences(): Pair<Boolean, Boolean> {
        var reduceMotion: Boolean? = null
        var touchExploration: Boolean? = null
        composeRule.setContent {
            ProvideMotionPreferences {
                reduceMotion = LocalReduceMotion.current
                touchExploration = LocalTouchExplorationActive.current
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(reduceMotion) to checkNotNull(touchExploration)
    }
}
