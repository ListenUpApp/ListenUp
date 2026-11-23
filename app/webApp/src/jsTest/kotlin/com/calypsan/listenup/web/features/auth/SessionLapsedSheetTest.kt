package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * The sheet a lapsed reader signs back in through, over the still-mounted shell.
 *
 * Every link it draws has to go somewhere. It once drew "Forgot password?" and "Redeem it" wired to
 * nothing: two controls that looked like a way forward and silently were not, in the one place a
 * reader is already locked out of something.
 */
class SessionLapsedSheetTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        suspend fun openSheet(graph: FakeAuthGraph): HTMLElement {
            val host = mounts.mount { WebAppSurface { SessionLapsedBanner(graph) } }
            (host.querySelector(".lapse-go") as HTMLElement).click()
            awaitFrame()
            return host
        }

        fun lapsed() = FakeAuthGraph(AuthState.SessionLapsed(UserId("u1")))

        test("the sheet offers no invite to redeem, because this reader already has an account") {
            // Claiming an invite here would sign a second account into a shell still holding the
            // first one's library. Android's re-auth screen offers no invite either.
            val host = openSheet(lapsed())

            host.linkTexts() shouldNotContain "Redeem it"
            host.textContent.orEmpty().contains("Have an invite code?") shouldBe false
        }

        test("a lapsed reader who has lost their password can start a reset from the sheet") {
            // Both native clients route re-auth's "Forgot password?" to the admin-approval flow:
            // finishing it revokes every session, so the reader lands back here with the new one.
            val graph = lapsed()
            val host = openSheet(graph)

            host.linkNamed("Forgot password?").click()
            awaitFrame()

            host.querySelector(".dlg-t")?.textContent shouldBe "Reset your password"
            val email = host.querySelector("#$RESET_EMAIL_ID") as HTMLInputElement
            email.value = "reader@example.com"
            email.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()
            awaitFrame()

            graph.resetRequestedFor shouldBe "reader@example.com"
        }

        test("the reset comes back to the sign-in form inside the same sheet") {
            val host = openSheet(lapsed())

            host.linkNamed("Forgot password?").click()
            awaitFrame()
            host.linkNamed("Back to sign in").click()
            awaitFrame()

            host.querySelector(".dlg-t")?.textContent shouldBe "Sign back in"
            host.linkTexts() shouldContain "Forgot password?"
        }
    })

private fun HTMLElement.linkTexts(): List<String> {
    val links = querySelectorAll(".lnk")
    return (0 until links.length).mapNotNull { (links.item(it) as? HTMLElement)?.textContent?.trim() }
}

private fun HTMLElement.linkNamed(text: String): HTMLElement {
    val links = querySelectorAll(".lnk")
    for (i in 0 until links.length) {
        val link = links.item(i) as? HTMLElement ?: continue
        if (link.textContent?.trim() == text) return link
    }
    error("no link named \"$text\"")
}
