package com.calypsan.listenup.web.shell

import com.calypsan.listenup.web.ViewportFrames
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
                NavEntry("search", "Search", WebIcon.Search, onPhone = false),
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
 * The shell on a phone: the sidebar becomes a bottom tab bar of Home · Library · Discover, and the phone header (see
 * [PhoneTopBarTest]) carries Search, notifications and the account's destinations, so nothing is lost.
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

        test("the tab bar is Home · Library · Discover as links, with the current one marked") {
            val frame = phone(active = "library")

            val tabs = frame.findAll(".sidebar .sb-nav:not(.sb-foot) .nav-i").filter(frame::isShown)
            tabs.map { it.tagName } shouldBe List(3) { "A" }
            tabs.map { it.getAttribute("href") } shouldBe listOf("/", "/library", "/discover")
            tabs.map { it.textContent.orEmpty().trim() } shouldBe listOf("Home", "Library", "Discover")
            tabs.filter { it.hasAttribute("aria-current") }.map { it.textContent.orEmpty().trim() } shouldBe
                listOf("Library")
        }

        test("every tab is a thumb-sized target, down to 320px") {
            val frame = phone()

            val targets = frame.findAll(".sidebar .nav-i").filter(frame::isShown)
            targets.size shouldBe 3
            targets.forEach { target ->
                frame.rect(target).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
                frame.rect(target).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
            }
            frame.horizontalOverflow() shouldBe 0
        }

        test("the footer's destinations leave the tab bar on a phone: the header carries them") {
            val frame = phone()

            frame.isShown(frame.find(".sb-foot")) shouldBe false
            frame.findAll(".sb-more") shouldBe emptyList()
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

        test("a tablet keeps the rail, with its footer and Search") {
            val frame = phone(width = TABLET)
            val rail = frame.find(".sidebar")

            frame.css(rail, "position") shouldBe "static"
            frame.rect(rail).width shouldBe RAIL_PX
            frame.isShown(frame.find(".sb-foot")) shouldBe true
            frame.findAll(".sidebar .nav-i").filter(frame::isShown).size shouldBe 7
        }
    })
