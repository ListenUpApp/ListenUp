package com.calypsan.listenup.client.features.nowplaying

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.client.features.nowplaying.components.rememberClockTimeFormatter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Pauses at 11:42 PM" follows the device's own 12/24-hour choice and locale (C16). */
@Config(qualifiers = "en-rUS")
@RunWith(RobolectricTestRunner::class)
class ClockTimeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var originalZone: TimeZone

    @Before
    fun useUtc() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun clockAt(hourSetting: String): String {
        Settings.System.putString(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            Settings.System.TIME_12_24,
            hourSetting,
        )
        composeRule.setContent {
            val format = rememberClockTimeFormatter()
            Text(format(ELEVEN_FORTY_TWO_PM), modifier = Modifier.testTag("clock"))
        }
        return composeRule
            .onNodeWithTag("clock")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
    }

    @Test
    fun `a 12-hour phone reads 11 42 PM`() {
        clockAt("12") shouldMatch Regex("""11:42\s?PM""")
    }

    @Test
    fun `a 24-hour phone reads 23 42`() {
        clockAt("24") shouldBe "23:42"
    }

    private companion object {
        /** 1970-01-01 23:42 UTC. */
        const val ELEVEN_FORTY_TWO_PM = (23 * 60 + 42) * 60_000L
    }
}
