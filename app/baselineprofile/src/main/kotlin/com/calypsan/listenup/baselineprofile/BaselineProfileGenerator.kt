package com.calypsan.listenup.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the ListenUp app.
 *
 * Run via: ./gradlew :app:androidApp:generateReleaseBaselineProfile
 *
 * The journeys below are deliberately scoped to what is reliably reachable
 * without a live server: cold launch, splash screen dismissal, and the
 * onboarding / server-select UI that the app shows to unauthenticated users.
 *
 * Journeys NOT covered here (deferred — add when a demo/test server exists):
 *   - Library browsing (requires an authenticated session with populated books)
 *   - Book detail / Now Playing (requires server + media)
 *   - Search (requires server)
 *   - Settings / Profile screens
 *
 * Even this partial profile covers the most expensive path: the cold-start
 * class-loading burst through Compose + Koin initialisation and the
 * onboarding flow, which is what every first-run user experiences.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() =
        baselineProfileRule.collect(packageName = APP_PACKAGE) {
            // --- Journey 1: Cold launch to first visible content ---
            // startActivityAndWait() launches MainActivity, waits for the first
            // frame to be drawn, and records all class-loading + JIT that happens
            // on the critical path. This alone covers Compose, Koin, Room, and
            // the Ktor client initialisation.
            pressHome()
            startActivityAndWait()

            // Wait for the splash screen to dismiss and the first real UI to appear.
            // The app shows either:
            //   (a) The server-select / onboarding screen (no server configured), or
            //   (b) The auth screen (server configured, not logged in).
            // Both are reachable without a server, so we wait for either.
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE).depth(0)), TIMEOUT_MS)

            // --- Journey 2: Navigate to manual server-entry UI ---
            // The onboarding screen has a "Connect to server" or "Add server" element
            // with content description "add_server" (set in the Compose screen).
            // If found, tap it to pre-warm the server-entry composable hierarchy.
            // If not found (e.g. an emulator that already has a server configured),
            // the journey is skipped gracefully — the profile is still valid.
            val addServerButton = device.findObject(By.desc("add_server"))
            if (addServerButton != null) {
                addServerButton.click()
                // Wait for the server-entry form to appear (text field for URL)
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
            }
        }

    private companion object {
        const val APP_PACKAGE = "com.calypsan.listenup.client"

        /** Maximum wait for a UI condition before giving up and continuing. */
        const val TIMEOUT_MS = 5_000L
    }
}
