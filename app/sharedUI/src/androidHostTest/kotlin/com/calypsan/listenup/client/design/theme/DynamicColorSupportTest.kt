package com.calypsan.listenup.client.design.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dynamic colour arrived in Android 12 and TVs have no wallpaper to derive it from. The theme and
 * the Settings switch ask the same question, so Android 11 is never offered a switch that does
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
class DynamicColorSupportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [30])
    fun `Android 11 has no dynamic colour`() {
        context.supportsDynamicColor() shouldBe false
    }

    @Test
    @Config(sdk = [34])
    fun `a phone on Android 14 has dynamic colour`() {
        context.supportsDynamicColor() shouldBe true
    }

    @Test
    fun `a television has no dynamic colour on any version`() {
        supportsDynamicColor(sdkInt = 34, isTelevision = true) shouldBe false
        supportsDynamicColor(sdkInt = 31, isTelevision = false) shouldBe true
        supportsDynamicColor(sdkInt = 30, isTelevision = false) shouldBe false
    }
}
