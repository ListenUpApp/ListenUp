package com.calypsan.listenup.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the ListenUp app.
 *
 * Two journeys:
 *
 * 1. **Onboarding** (always): cold launch, splash dismissal and the manual server-entry form —
 *    what every first-run user sees.
 * 2. **Returning user** (when a rig server is supplied): sign in, then Home → Library grid fling
 *    → Book detail → Play → Now Playing. This is what a returning user cold-starts into every
 *    day, and without it those screens run un-AOT'd.
 *
 * The second journey needs a signed-in app against a **seeded local rig server** (at least one
 * playable book) — never a production server. The server and account come from instrumentation
 * arguments, so nothing is hardcoded or committed:
 *
 * ```
 * ./gradlew :app:androidApp:generateReleaseBaselineProfile \
 *   -Pandroid.testInstrumentationRunnerArguments.listenupServerUrl=http://10.0.2.2:8080 \
 *   -Pandroid.testInstrumentationRunnerArguments.listenupEmail=<rig account> \
 *   -Pandroid.testInstrumentationRunnerArguments.listenupPassword="$(op read 'op://…')"
 * ```
 *
 * (`10.0.2.2` is the emulator's alias for the host's loopback.) Without the arguments, only the
 * onboarding journey runs and the profile still generates.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val rig: RigAccount? = RigAccount.fromInstrumentationArguments()

    @Test
    fun generate() =
        baselineProfileRule.collect(packageName = APP_PACKAGE) {
            grantRuntimePermissions()
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE).depth(0)), TIMEOUT_MS)

            val account = rig
            if (account == null) {
                openServerEntry()
            } else {
                signInIfNeeded(account)
                browseAndPlay()
            }
        }

    /** The system dialogs would otherwise cover the screens being profiled. */
    private fun MacrobenchmarkScope.grantRuntimePermissions() {
        RUNTIME_PERMISSIONS.forEach { permission ->
            device.executeShellCommand("pm grant $APP_PACKAGE $permission")
        }
    }

    /** Journey 1: the manual server-entry form, reachable with no server at all. */
    private fun MacrobenchmarkScope.openServerEntry() {
        device.findObject(By.text(ADD_SERVER_MANUALLY))?.let { addServer ->
            addServer.click()
            device.wait(Until.hasObject(By.clazz(EDIT_TEXT)), TIMEOUT_MS)
        }
    }

    /**
     * Connects to the rig and signs in, or does nothing when an earlier iteration already did —
     * the rule keeps app data between iterations, so later ones start on Home.
     */
    private fun MacrobenchmarkScope.signInIfNeeded(account: RigAccount) {
        if (device.wait(Until.hasObject(By.text(LIBRARY_TAB)), TIMEOUT_MS)) return

        device.findObject(By.text(ADD_SERVER_MANUALLY))?.click()
        device.wait(Until.hasObject(By.clazz(EDIT_TEXT)), TIMEOUT_MS)
        device.findObject(By.clazz(EDIT_TEXT))?.text = account.serverUrl
        device.findObject(By.text(CONNECT))?.click()

        device.wait(Until.hasObject(By.text(FORGOT_PASSWORD)), NETWORK_TIMEOUT_MS)
        val fields = device.findObjects(By.clazz(EDIT_TEXT))
        check(fields.size >= 2) { "Sign-in form not shown; is the rig server at ${account.serverUrl} running?" }
        fields[0].text = account.email
        fields[1].text = account.password
        // Two nodes read "Sign In": the screen title, then the button.
        device.findObjects(By.text(SIGN_IN)).last().click()

        check(device.wait(Until.hasObject(By.text(LIBRARY_TAB)), NETWORK_TIMEOUT_MS)) {
            "Sign-in did not reach Home; check the rig account"
        }
    }

    /** Journey 2: Home → Library fling → Book detail → Play → Now Playing, then back out. */
    private fun MacrobenchmarkScope.browseAndPlay() {
        device.waitForIdle()
        device.findObject(By.text(LIBRARY_TAB))?.click()

        // A book card is the first cover-shaped (taller than wide) clickable; tabs, nav items and
        // toolbar buttons never are. Waiting for one also waits for the library to sync in.
        val bookCard = { device.findObjects(By.clickable(true)).firstOrNull { it.isCoverShaped() } }
        if (!device.wait({ bookCard() != null }, NETWORK_TIMEOUT_MS)) return

        // The library grid is the largest scrollable. With too few books to scroll there is no
        // such grid, and the fling lands on a row that does not move — harmless.
        device
            .findObjects(By.scrollable(true))
            .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            ?.let { grid ->
                grid.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
                grid.fling(Direction.DOWN)
                device.waitForIdle()
                grid.fling(Direction.UP)
                device.waitForIdle()
            }

        val firstBook = bookCard() ?: return
        firstBook.click()
        val play = device.wait(Until.findObject(By.text(PLAY)), TIMEOUT_MS) ?: return
        play.click()

        device.wait(Until.findObject(By.desc(MINI_PLAYER_COVER)), NETWORK_TIMEOUT_MS)?.click()
        device.wait(Until.hasObject(By.text(NOW_PLAYING)), TIMEOUT_MS)
        device.waitForIdle()

        // Leave the rig quiet for the next iteration: stop playback and return to Home.
        device.findObject(By.desc(PAUSE))?.click()
        repeat(BACK_PRESSES) { device.pressBack() }
    }

    /** A node recomposed away between the query and this read is stale: not a card, re-query. */
    private fun UiObject2.isCoverShaped(): Boolean =
        try {
            visibleBounds.height() > visibleBounds.width()
        } catch (_: StaleObjectException) {
            false
        }

    /** The rig server and account supplied through instrumentation arguments. */
    private data class RigAccount(
        val serverUrl: String,
        val email: String,
        val password: String,
    ) {
        companion object {
            fun fromInstrumentationArguments(): RigAccount? {
                val arguments = InstrumentationRegistry.getArguments()
                val serverUrl = arguments.getString(ARG_SERVER_URL)
                val email = arguments.getString(ARG_EMAIL)
                val password = arguments.getString(ARG_PASSWORD)
                if (serverUrl.isNullOrBlank() || email.isNullOrBlank() || password.isNullOrBlank()) return null
                return RigAccount(serverUrl = serverUrl, email = email, password = password)
            }
        }

        /** Never print the password, even in a failure message. */
        override fun toString(): String = "RigAccount(serverUrl=$serverUrl, email=$email)"
    }

    private companion object {
        const val APP_PACKAGE = "com.calypsan.listenup.client"

        const val ARG_SERVER_URL = "listenupServerUrl"
        const val ARG_EMAIL = "listenupEmail"
        const val ARG_PASSWORD = "listenupPassword"

        // ACCESS_LOCAL_NETWORK (API 37+) gates every connection to a LAN or emulator-host
        // server; ungranted, the rig is unreachable. Granting a permission a platform does not
        // define just prints an error, so the list is safe on every API level.
        val RUNTIME_PERMISSIONS =
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_LOCAL_NETWORK",
            )

        // On-screen text and accessibility labels (English resources).
        const val ADD_SERVER_MANUALLY = "Add Server Manually"
        const val CONNECT = "Connect"
        const val FORGOT_PASSWORD = "Forgot password?"
        const val SIGN_IN = "Sign In"
        const val LIBRARY_TAB = "Library"
        const val PLAY = "Play"
        const val MINI_PLAYER_COVER = "Book cover"
        const val NOW_PLAYING = "NOW PLAYING"
        const val PAUSE = "Pause"
        const val EDIT_TEXT = "android.widget.EditText"

        /** Maximum wait for a local UI condition. */
        const val TIMEOUT_MS = 5_000L

        /** Maximum wait for a condition that needs the rig server to answer. */
        const val NETWORK_TIMEOUT_MS = 15_000L

        /** Keeps the fling clear of the system gesture areas at the screen edges. */
        const val GESTURE_MARGIN_DIVISOR = 5

        /** Now Playing → Book detail → Library → Home. */
        const val BACK_PRESSES = 3
    }
}
