package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import com.calypsan.listenup.web.zoomTextTo200
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.browser.document
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val SAVED = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simonhull", 1L))

private fun active(): HTMLElement? = document.activeElement as? HTMLElement

/** Frames until [done], for what an effect writes a frame after the state that caused it. */
private suspend fun awaitUntil(done: () -> Boolean) {
    repeat(MAX_FRAMES) {
        if (done()) return
        awaitFrame()
    }
}

private const val MAX_FRAMES = 30

private fun HTMLElement.buttonStartingWith(words: String): HTMLButtonElement =
    querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .first {
            it.textContent
                .orEmpty()
                .trim()
                .startsWith(words)
        }

/**
 * Admin → Hardcover's API token (#1562 on web): focus lands where each step leaves it, and checking,
 * refusal and success are each said aloud through one region that was already listening.
 */
class HardcoverSourceSectionAccessibilityTest :
    FunSpec({
        val mounts = MountRegistry()
        val frames = ViewportFrames()
        afterTest {
            mounts.disposeAll()
            frames.disposeAll()
            (document.activeElement as? HTMLElement)?.blur()
        }

        class Section(
            val host: HTMLElement,
            val setStatus: (HardcoverSourceStatus) -> Unit,
            val setSave: (HardcoverTokenSave) -> Unit,
        ) {
            val status get() = host.querySelector("p.sr-only[role=status]").shouldNotBeNull() as HTMLElement
        }

        fun section(initial: HardcoverSourceStatus): Section {
            var status by mutableStateOf(initial)
            var save by mutableStateOf<HardcoverTokenSave>(HardcoverTokenSave.Idle)
            val host =
                mounts.mount {
                    HardcoverSourceSection(
                        status = status,
                        tokenSave = save,
                        onSaveToken = {},
                        onRemoveToken = {},
                        onMetadata = {},
                        onClearTokenError = {},
                    )
                }
            return Section(host, { status = it }, { save = it })
        }

        test("M-F1, M-F2: Save keeps focus while Hardcover checks, then lands on whose token it is — each step said") {
            val section = section(HardcoverSourceStatus())
            awaitFrame()
            section.status.textContent shouldBe ""
            val field = section.host.querySelector("#hc-token") as HTMLInputElement
            field.value = "hc_token"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            val save = section.host.buttonStartingWith("Save token")
            save.focus()

            section.setSave(HardcoverTokenSave.Busy)
            awaitFrame()
            active() shouldBe save
            save.hasAttribute("disabled") shouldBe false
            save.getAttribute("aria-disabled") shouldBe "true"
            awaitUntil { section.status.textContent == "Checking with Hardcover…" }
            section.status.textContent shouldBe "Checking with Hardcover…"

            section.setStatus(SAVED)
            section.setSave(HardcoverTokenSave.Idle)
            awaitUntil { section.status.textContent == "Saved — belongs to @simonhull" }
            active().shouldNotBeNull().textContent shouldBe "Set · belongs to @simonhull"
            section.status.textContent shouldBe "Saved — belongs to @simonhull"
        }

        test("M-F2: a refusal is said aloud as well as written beside the field") {
            val section = section(HardcoverSourceStatus())
            awaitFrame()

            section.setSave(HardcoverTokenSave.Busy)
            awaitUntil { section.status.textContent == "Checking with Hardcover…" }
            section.setSave(HardcoverTokenSave.Refused(HardcoverError.TokenRejected()))
            awaitUntil { section.status.textContent != "Checking with Hardcover…" }

            section.status.textContent shouldBe "Hardcover didn't accept that token. Check it and try again."
        }

        test("M-F1: Replace puts focus in the token field") {
            val section = section(SAVED)
            awaitFrame()
            val replace = section.host.buttonStartingWith("Replace")
            replace.focus()
            replace.click()
            awaitFrame()
            awaitFrame()

            active() shouldBe section.host.querySelector("#hc-token")
        }

        test("M-W2: Cancel on Remove hands focus back to Remove") {
            val section = section(SAVED)
            awaitFrame()
            val remove = section.host.buttonStartingWith("Remove")
            remove.focus()
            remove.click()
            awaitFrame()

            (document.querySelector("dialog .dlg-actions button") as HTMLElement).click()
            awaitFrame()

            active() shouldBe remove
        }

        test("m-F4, m-F5, m-F6: Replace and Remove name the token, the link says it opens a tab, the switch has its sentence") {
            val section = section(SAVED)
            awaitFrame()

            section.host.buttonStartingWith("Replace").textContent shouldBe "Replace API token"
            section.host.buttonStartingWith("Remove").textContent shouldBe "Remove API token"
            val link = section.host.querySelector("a[target=_blank]").shouldNotBeNull()
            link.textContent shouldBe "Get a token from Hardcover (opens in a new tab)"
            val metadata =
                section.host
                    .querySelectorAll(".sw-in")
                    .asList()
                    .last() as HTMLInputElement
            val note = document.getElementById(metadata.getAttribute("aria-describedby").shouldNotBeNull()).shouldNotBeNull()
            note.textContent.orEmpty() shouldStartWith "Offer Hardcover's moods"
        }

        test("M-F3: on a phone at 200% text the rows keep their words readable and Remove is wholly on screen") {
            val frame =
                frames.mount(SMALL_PHONE) {
                    InShell {
                        ServerSettingsPage(
                            state = readyServerSettings().copy(hardcoverSource = SAVED),
                            onServerName = {},
                            onRemoteUrl = {},
                            onHoldNewBooks = {},
                            onPushNotifications = {},
                            onSave = {},
                            onClearError = {},
                            onRetry = {},
                            onOpenAdmin = {},
                        )
                    }
                }
            frame.zoomTextTo200()

            withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
            frame.findAll(".srv-toggle-d").forEach { words ->
                withClue("${words.textContent} is ${frame.rect(words).width}px wide") {
                    frame.rect(words).width shouldBeGreaterThanOrEqual 120.0
                }
            }
            val remove = frame.findAll(".btn-danger").single()
            val section = remove.closest("section").shouldNotBeNull()
            frame.rect(remove).right shouldBeLessThanOrEqual frame.rect(section).right
        }
    })
