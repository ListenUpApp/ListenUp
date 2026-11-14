package com.calypsan.listenup.storeshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Captures the Play listing's screenshots against a seeded screenshot-rig server.
 *
 * Run by `tools/scripts/store-screenshots-android.sh`, once per listing size, on an emulator the script
 * has already prepared: app data cleared, system demo mode on (9:41, full battery and Wi-Fi), light
 * mode, sound off. The suite connects, signs in, switches Dynamic colors off so the brand coral shows,
 * and writes one PNG per screen into [ARG_OUTPUT_DIR] on the device.
 *
 * The account comes from a file the script pushes to the device and this suite deletes once read, so
 * the password is never on a command line or in a log. Without the arguments the suite is skipped.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun captureStoreScreenshots() {
        val serverUrl = arguments.getString(ARG_SERVER_URL)
        val accountFile = arguments.getString(ARG_ACCOUNT_FILE)
        assumeTrue("No screenshot rig supplied", !serverUrl.isNullOrBlank() && !accountFile.isNullOrBlank())
        val account = readAccount(checkNotNull(accountFile))
        val outputDir = arguments.getString(ARG_OUTPUT_DIR) ?: DEFAULT_OUTPUT_DIR
        shell("mkdir -p $outputDir")
        val shots = Shots(outputDir)

        launchApp()
        // Signing in and the brand colours are preconditions; every screen after them is captured
        // independently, so one moved label costs one screenshot, not the rest of the set.
        step("sign in") { signIn(checkNotNull(serverUrl), account) }
        step("brand colours") { turnOffDynamicColors() }
        val missed =
            listOfNotNull(
                screen("home") {
                    await(By.descStartsWith(SHERLOCK), "Sherlock on Home", LONG_TIMEOUT_MS)
                    settle(SLOW_SETTLE_MS)
                    shots.capture("01-home")
                },
                screen("library") {
                    navigate(LIBRARY)
                    settle(SLOW_SETTLE_MS)
                    shots.capture("04-library")
                },
                screen("discover") {
                    navigate(DISCOVER)
                    settle(SLOW_SETTLE_MS * 2)
                    shots.capture("05-discover")
                },
                screen("book detail") {
                    navigate(HOME)
                    openBook(SHERLOCK)
                    settle(SLOW_SETTLE_MS)
                    shots.capture("03-book-detail")
                    scrollToReaders()
                    shots.capture("03b-book-detail-readers")
                },
                // Downloads before Now Playing, in case starting playback also starts a download of the book.
                screen("downloads") { captureDownloads(shots) },
                screen("now playing") { captureNowPlaying(shots) },
                // Last, signed out again: the brand colours only apply once Settings has been reached.
                screen("connect") { captureConnect(shots) },
            )

        device.pressHome()
        shell("am force-stop $APP_PACKAGE")
        check(missed.isEmpty()) { "Store screenshots missed:\n${missed.joinToString("\n")}" }
    }

    // ── Flow ────────────────────────────────────────────────────────────────

    private fun launchApp() {
        device.pressHome()
        shell("am start -W -n $APP_PACKAGE/$MAIN_ACTIVITY")
        check(device.wait(Until.hasObject(By.pkg(APP_PACKAGE).depth(0)), LONG_TIMEOUT_MS)) { "The app did not start" }
    }

    private fun signIn(
        serverUrl: String,
        account: Account,
    ) {
        await(By.text(ADD_SERVER_MANUALLY), "Add server manually").click()
        await(By.clazz(EDIT_TEXT), "the server URL field").text = serverUrl
        await(By.text(CONNECT), "Connect").click()

        await(By.text(FORGOT_PASSWORD), "the sign-in form", LONG_TIMEOUT_MS)
        val fields = device.findObjects(By.clazz(EDIT_TEXT))
        check(fields.size >= 2) { "The sign-in form has ${fields.size} fields" }
        fields[0].text = account.email
        fields[1].text = account.password
        // Two nodes read "Sign in": the screen title, then the button.
        device.findObjects(By.text(SIGN_IN)).last().click()
        await(By.text(LIBRARY), "Home after signing in", LONG_TIMEOUT_MS)
    }

    /** Settings > Appearance > Dynamic colors off, so the brand coral shows instead of the wallpaper's. */
    private fun turnOffDynamicColors() {
        openSettings()
        val toggle = scrollTo(By.text(DYNAMIC_COLORS))
        val switch =
            toggle.parent?.findObject(By.checkable(true)) ?: toggle.parent?.parent?.findObject(By.checkable(true))
        if (switch == null || switch.isChecked) toggle.click()
        device.waitForIdle()
        device.pressBack()
        await(By.text(LIBRARY), "Home after Settings")
    }

    private fun openSettings() {
        await(By.text(INITIALS), "the account menu").click()
        await(By.text(SETTINGS), "Settings in the account menu").click()
        await(By.text(APPEARANCE_OR_SETTINGS), "the Settings screen")
    }

    private fun openBook(titlePrefix: String) {
        val card =
            device.findObject(By.descStartsWith(titlePrefix).clickable(true))
                ?: device.findObject(By.textStartsWith(titlePrefix))?.let { clickableAncestor(it) }
        checkNotNull(card) { "No card for $titlePrefix" }.click()
        await(By.descStartsWith(DOWNLOAD_PREFIX), "the book's page", LONG_TIMEOUT_MS)
    }

    private fun scrollToReaders() {
        scrollTo(By.text(READERS))
        settle()
    }

    private fun captureNowPlaying(shots: Shots) {
        navigate(HOME)
        openBook(SHERLOCK)
        await(By.text(RESUME), "Resume").click()
        await(By.res(MINI_BODY_TAG), "the mini player", LONG_TIMEOUT_MS).click()
        await(By.desc(COLLAPSE_PLAYER), "Now Playing")
        pause()
        settle(SLOW_SETTLE_MS)
        shots.capture("02-now-playing")
        device.findObject(By.desc(COLLAPSE_PLAYER))?.click()
        device.waitForIdle()
    }

    /**
     * Pauses Now Playing and waits until it shows Play. A tap that lands while playback is still starting
     * can be swallowed, so it retries rather than trusting one press.
     */
    private fun pause() {
        repeat(PAUSE_ATTEMPTS) {
            if (device.wait(Until.hasObject(By.desc(PLAY)), TIMEOUT_MS)) return
            device.findObject(By.desc(PAUSE))?.click()
        }
        await(By.desc(PLAY), "the paused player")
    }

    private fun captureConnect(shots: Shots) {
        navigate(HOME)
        await(By.text(INITIALS), "the account menu").click()
        await(By.text(SIGN_OUT), "Sign out in the account menu").click()
        // A confirmation, when there is one, ends with the same words.
        device.wait(Until.findObject(By.text(SIGN_OUT)), TIMEOUT_MS)?.let {
            device.findObjects(By.text(SIGN_OUT)).last().click()
        }
        // Signing out keeps the server and lands on its sign-in form; one step further back is the picker.
        await(By.text(CHANGE_SERVER), "Change server on the sign-in form", LONG_TIMEOUT_MS).click()
        await(By.text(ADD_SERVER_MANUALLY), "the connect screen", LONG_TIMEOUT_MS)
        settle(SLOW_SETTLE_MS)
        shots.capture("07-connect")
    }

    private fun captureDownloads(shots: Shots) {
        navigate(HOME)
        await(By.desc(SEARCH), "Search").click()
        val query = await(By.clazz(EDIT_TEXT), "the search field")
        query.text = DOWNLOAD_TITLE
        await(By.textContains(RESULTS_FOR), "the search results", LONG_TIMEOUT_MS)
        // With the keyboard up, the first tap only closes it.
        if (shell("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
        // The hit's title is the last node with that text; the first is the query in the search field.
        device.findObjects(By.text(DOWNLOAD_TITLE)).last().click()
        await(By.descStartsWith(DOWNLOAD_PREFIX), "the download button", LONG_TIMEOUT_MS).click()
        check(
            device.wait(Until.gone(By.descStartsWith(DOWNLOAD_PREFIX)), DOWNLOAD_TIMEOUT_MS),
        ) { "The download never started" }
        // While it runs, the button reads its progress ("49%").
        check(device.wait(Until.gone(By.descEndsWith("%")), DOWNLOAD_TIMEOUT_MS)) { "The download never finished" }
        navigate(HOME)
        openSettings()
        scrollTo(By.text(MANAGE_STORAGE)).click()
        settle(SLOW_SETTLE_MS)
        shots.capture("06-downloads")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Backs out of any detail screen, sheet or keyboard until the navigation bar shows, then picks [destination]. */
    private fun navigate(destination: String) {
        backUntil(By.text(destination))
        await(By.text(destination), "the $destination destination").click()
        device.waitForIdle()
    }

    /** Presses Back, at most [BACK_PRESSES] times, until [selector] is on screen. */
    private fun backUntil(selector: BySelector) {
        var presses = 0
        while (!device.wait(Until.hasObject(selector), BACK_SETTLE_MS) && presses++ < BACK_PRESSES) {
            device.pressBack()
        }
    }

    private fun scrollTo(selector: BySelector): UiObject2 {
        repeat(MAX_SCROLLS) {
            device.findObject(selector)?.let { return it }
            val scrollable =
                device
                    .findObjects(By.scrollable(true))
                    .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
                    ?: return@repeat
            scrollable.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
            scrollable.scroll(Direction.DOWN, SCROLL_PERCENT)
            device.waitForIdle()
        }
        return await(selector, selector.toString())
    }

    private fun clickableAncestor(node: UiObject2): UiObject2? {
        var current: UiObject2? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

    private fun await(
        selector: BySelector,
        what: String,
        timeout: Long = TIMEOUT_MS,
    ): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Not found: $what ($selector)" }

    /**
     * Runs [block], one screen's capture; on failure records the evidence, returns to Home and returns
     * what went wrong, so the run can carry on and report every miss at the end.
     */
    private fun screen(
        name: String,
        block: () -> Unit,
    ): String? =
        try {
            step(name, block)
            null
        } catch (failure: AssertionError) {
            println(failure.message)
            backUntil(By.text(INITIALS))
            failure.message ?: name
        }

    /** Runs [block]; on failure saves the screen and its hierarchy, named for the step, then rethrows. */
    private fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: Throwable) {
            val dir = arguments.getString(ARG_OUTPUT_DIR) ?: DEFAULT_OUTPUT_DIR
            val slug = name.replace(' ', '-')
            shell("screencap -p $dir/failed-$slug.png")
            instrumentation.context.getExternalFilesDir(null)?.let { files ->
                device.dumpWindowHierarchy(File(files, "failed-$slug.xml"))
            }
            throw AssertionError("Store screenshots stopped at '$name': ${failure.message}", failure)
        }
    }

    private fun settle(millis: Long = SETTLE_MS) {
        device.waitForIdle()
        Thread.sleep(millis)
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    /** Reads, then deletes, the two-line account file (email, password) the script pushed. */
    private fun readAccount(path: String): Account {
        val lines = shell("cat $path").lines()
        shell("rm -f $path")
        check(lines.size >= 2 && lines[0].isNotBlank() && lines[1].isNotBlank()) { "The account file is incomplete" }
        return Account(email = lines[0].trim(), password = lines[1].trim())
    }

    private inner class Shots(
        private val dir: String,
    ) {
        fun capture(name: String) {
            device.waitForIdle()
            shell("screencap -p $dir/$name.png")
            instrumentation.context.getExternalFilesDir(null)?.let { files ->
                device.dumpWindowHierarchy(File(files, "$name.xml"))
            }
        }
    }

    private data class Account(
        val email: String,
        val password: String,
    ) {
        /** Never print the password, even in a failure message. */
        override fun toString(): String = "Account(email=$email)"
    }

    private companion object {
        const val APP_PACKAGE = "com.calypsan.listenup.client"
        const val MAIN_ACTIVITY = "com.calypsan.listenup.client.MainActivity"

        const val ARG_SERVER_URL = "screenshotsServerUrl"
        const val ARG_ACCOUNT_FILE = "screenshotsAccountFile"
        const val ARG_OUTPUT_DIR = "screenshotsOutputDir"
        const val DEFAULT_OUTPUT_DIR = "/data/local/tmp/storeshots"

        // On-screen text and accessibility labels (English resources).
        const val ADD_SERVER_MANUALLY = "Add server manually"
        const val CONNECT = "Connect"
        const val FORGOT_PASSWORD = "Forgot password?"
        const val SIGN_IN = "Sign in"
        const val HOME = "Home"
        const val LIBRARY = "Library"
        const val DISCOVER = "Discover"
        const val SETTINGS = "Settings"
        const val SIGN_OUT = "Sign out"

        /** The account menu is the main listener's avatar: Maya Okafor's initials. */
        const val INITIALS = "MO"
        val APPEARANCE_OR_SETTINGS: Pattern = Pattern.compile("Appearance|Settings")
        const val DYNAMIC_COLORS = "Dynamic colors"

        /** Settings' way into the downloads manager. */
        const val MANAGE_STORAGE = "Manage storage"
        const val CHANGE_SERVER = "Change server"
        const val SEARCH = "Search"
        const val READERS = "Readers"
        const val RESUME = "Resume"
        const val PLAY = "Play"
        const val PAUSE = "Pause"
        const val DOWNLOAD_PREFIX = "Download, "
        const val RESULTS_FOR = "results for"
        const val COLLAPSE_PLAYER = "Collapse player"

        /** The mini player body's test tag, exposed as a resource id by the app root. */
        const val MINI_BODY_TAG = "mini-body"
        const val EDIT_TEXT = "android.widget.EditText"

        const val SHERLOCK = "The Adventures of Sherlock Holmes"

        /** Short enough to download in seconds: the rig's 72-minute recording. */
        const val DOWNLOAD_TITLE = "The Art of War"

        const val TIMEOUT_MS = 5_000L
        const val LONG_TIMEOUT_MS = 30_000L
        const val DOWNLOAD_TIMEOUT_MS = 180_000L
        const val SETTLE_MS = 1_500L
        const val SLOW_SETTLE_MS = 4_000L
        const val MAX_SCROLLS = 8
        const val SCROLL_PERCENT = 0.6f
        const val GESTURE_MARGIN_DIVISOR = 5
        const val BACK_PRESSES = 4
        const val PAUSE_ATTEMPTS = 5
        const val BACK_SETTLE_MS = 2_000L
    }
}
