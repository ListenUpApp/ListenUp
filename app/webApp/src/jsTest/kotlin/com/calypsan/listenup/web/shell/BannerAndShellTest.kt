package com.calypsan.listenup.web.shell

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.TABLET
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.features.auth.FakeAuthGraph
import com.calypsan.listenup.web.features.auth.SessionLapsedBanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

private const val DESKTOP = 1440

/**
 * The "Signed out" banner and the shell share the screen; they do not stack past it.
 *
 * The shell is exactly a viewport tall (`100dvh`) so that its content region, not the document,
 * is what scrolls. Put a banner above it and the document became a banner taller than the screen:
 * the whole app scrolled by the banner's height, at every width, and on a phone the tab bar and
 * the player slid under the fold. The two now divide one viewport between them.
 */
class BannerAndShellTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(PHONE, TABLET, DESKTOP).forEach { width ->
            test("at ${width}px a signed-out reader's app does not scroll as a whole") {
                val frame =
                    frames.mount(width) {
                        SessionLapsedBanner(FakeAuthGraph(AuthState.SessionLapsed(UserId("u1"))))
                        InShell { Div { Text("BODY") } }
                    }

                frame.verticalOverflow() shouldBe 0
                // The banner is still there, above the shell, and the shell ends at the screen's foot.
                frame.rect(frame.find(".lapse")).top shouldBe 0.0
                frame.rect(frame.find(".lapse")).bottom shouldBeLessThanOrEqual frame.rect(frame.find(".shell")).top
                frame.rect(frame.find(".shell")).bottom shouldBe frame.height.toDouble()
            }
        }

        test("without a banner the shell is still exactly the screen") {
            val frame = frames.mount(TABLET) { InShell { Div { Text("BODY") } } }

            frame.verticalOverflow() shouldBe 0
            frame.rect(frame.find(".shell")).height shouldBe frame.height.toDouble()
        }
    })
