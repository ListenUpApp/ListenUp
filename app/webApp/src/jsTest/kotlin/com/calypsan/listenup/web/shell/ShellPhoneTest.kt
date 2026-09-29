package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebIcon
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

private val PRIMARY =
    NavSection(
        entries =
            listOf(
                NavEntry("home", "Home", WebIcon.Home, href = "/"),
                NavEntry("library", "Library", WebIcon.Book),
                NavEntry("discover", "Discover", WebIcon.Compass),
                NavEntry("search", "Search", WebIcon.Search),
            ),
    )

private val FOOTER =
    listOf(
        NavEntry("notifications", "Notifications", WebIcon.Bell, badge = 4),
        NavEntry("admin", "Admin", WebIcon.Shield),
        NavEntry("settings", "Settings", WebIcon.Cog),
    )

private const val PHONE = 320

private const val LARGE_PHONE = 390

private const val TABLET = 768

/** WCAG 2.5.5's target size, and the one every platform guideline agrees on. */
private const val MIN_TARGET_PX = 44.0

/** The rail's width, from `06-shell.css`. */
private const val RAIL_PX = 76.0

/**
 * The shell on a phone: the sidebar becomes a bottom tab bar, and nothing it used to offer is lost.
 *
 * Measured, not inferred — each spec renders into a frame of the stated width (see
 * [ViewportFrames]), so the media queries, the fixed positioning and the target sizes are the ones
 * a phone would actually get.
 */
class ShellPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun phone(
            width: Int = PHONE,
            active: String = "library",
            onNavigate: (String) -> Unit = {},
        ) = frames.mount(width) {
            Shell(sections = listOf(PRIMARY), footer = FOOTER, active = active, onNavigate = onNavigate) {
                Div(attrs = { classes("page-body") }) { Text("BODY") }
            }
        }

        test("below 760px the sidebar is a bar pinned to the bottom of the screen") {
            val frame = phone()
            val bar = frame.find(".sidebar")
            val box = frame.rect(bar)

            frame.css(bar, "position") shouldBe "fixed"
            box.width shouldBe PHONE.toDouble()
            box.bottom shouldBe frame.height.toDouble()
            // The brand and the collapse toggle belong to the sidebar form; neither survives it.
            frame.isShown(frame.find(".sb-brand")) shouldBe false
        }

        test("the tab bar carries the primary destinations as links, with the current one marked") {
            val frame = phone(active = "library")

            val tabs = frame.findAll(".sidebar .sb-nav:not(.sb-foot) .nav-i").filter(frame::isShown)
            tabs.map { it.tagName } shouldBe List(4) { "A" }
            tabs.map { it.getAttribute("href") } shouldBe listOf("/", "/library", "/discover", "/search")
            tabs.map { it.textContent.orEmpty().trim() } shouldBe listOf("Home", "Library", "Discover", "Search")
            tabs.filter { it.hasAttribute("aria-current") }.map { it.textContent.orEmpty().trim() } shouldBe
                listOf("Library")
        }

        test("every tab is a thumb-sized target, down to 320px") {
            val frame = phone()

            val targets = frame.findAll(".sidebar .nav-i, .sidebar .sb-more").filter(frame::isShown)
            targets.size shouldBe 5
            targets.forEach { target ->
                frame.rect(target).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
                frame.rect(target).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
            }
            frame.horizontalOverflow() shouldBe 0
        }

        test("the rest of the destinations wait behind More, and More says when they are hiding news") {
            val frame = phone()
            val more = frame.find(".sb-more")

            more.tagName shouldBe "BUTTON"
            more.getAttribute("aria-expanded") shouldBe "false"
            frame.isShown(frame.find(".sb-foot")) shouldBe false
            // The unread count must not vanish into a closed menu: More carries it.
            frame.find(".sb-more .nav-badge").getAttribute("aria-label") shouldBe "4 unread"

            more.click()
            awaitFrame()

            more.getAttribute("aria-expanded") shouldBe "true"
            val entries = frame.findAll(".sb-foot .nav-i").filter(frame::isShown)
            entries.map { it.tagName } shouldBe List(3) { "A" }
            entries.map { it.getAttribute("href") } shouldBe listOf("/notifications", "/admin", "/settings")
            entries.forEach { frame.rect(it).height shouldBeGreaterThanOrEqual MIN_TARGET_PX }
        }

        test("choosing a destination from More routes there and puts More away") {
            var chosen: String? = null
            val frame = phone(onNavigate = { chosen = it })
            frame.find(".sb-more").click()
            awaitFrame()

            frame.findAll(".sb-foot .nav-i")[2].click()
            awaitFrame()

            chosen shouldBe "settings"
            frame.find(".sb-more").getAttribute("aria-expanded") shouldBe "false"
            frame.isShown(frame.find(".sb-foot")) shouldBe false
        }

        test("Escape puts More away") {
            val frame = phone()
            val more = frame.find(".sb-more")
            more.click()
            awaitFrame()

            frame.find(".sb-foot .nav-i").dispatchEvent(
                org.w3c.dom.events.KeyboardEvent(
                    "keydown",
                    org.w3c.dom.events
                        .KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true),
                ),
            )
            awaitFrame()

            more.getAttribute("aria-expanded") shouldBe "false"
        }

        test("on a More page, More is the lit tab") {
            val frame = phone(active = "settings")

            frame.find(".sb-more").classList.contains("on") shouldBe true
        }

        test("content never sits under the tab bar") {
            val frame = phone()

            val main = frame.rect(frame.find(".shell-main"))
            main.bottom shouldBeLessThanOrEqual frame.rect(frame.find(".sidebar")).top
            // 14px, not the desktop 28: at 320px every pixel of gutter is taken from the content.
            frame.css(frame.find(".shell-main"), "padding-left") shouldBe "14px"
        }

        test("the skip link and the named landmarks survive the phone layout") {
            val frame = phone(width = LARGE_PHONE)

            frame.find(".skip-link").getAttribute("href") shouldBe "#main-content"
            frame.findAll("nav").map { it.getAttribute("aria-label") } shouldBe listOf("Main", "Account")
        }

        test("a tablet keeps the rail, and More is a phone-only affordance") {
            val frame = phone(width = TABLET)
            val rail = frame.find(".sidebar")

            frame.css(rail, "position") shouldBe "static"
            frame.rect(rail).width shouldBe RAIL_PX
            frame.isShown(frame.find(".sb-more")) shouldBe false
            frame.isShown(frame.find(".sb-foot")) shouldBe true
        }
    })
