package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun readyServerSettings(
    serverName: String = "Simon's Library",
    remoteUrl: String = "",
    holdNewBooksForReview: Boolean = false,
    pushNotificationsEnabled: Boolean = true,
    ratingSources: List<RatingSourceStatus> = emptyList(),
    isDirty: Boolean = false,
    isSaving: Boolean = false,
    error: com.calypsan.listenup.api.error.AppError? = null,
    metadataRegion: String = "us",
): AdminSettingsUiState.Ready =
    AdminSettingsUiState.Ready(
        serverName = serverName,
        remoteUrl = remoteUrl,
        holdNewBooksForReview = holdNewBooksForReview,
        pushNotificationsEnabled = pushNotificationsEnabled,
        ratingSources = ratingSources,
        isDirty = isDirty,
        isSaving = isSaving,
        error = error,
        metadataRegion = metadataRegion,
    )

@Suppress("LongParameterList")
private fun page(
    state: AdminSettingsUiState,
    onServerName: (String) -> Unit = {},
    onRemoteUrl: (String) -> Unit = {},
    onHoldNewBooks: (Boolean) -> Unit = {},
    onPushNotifications: (Boolean) -> Unit = {},
    onSetRatingSourceEnabled: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    onSaveHardcoverToken: (String) -> Unit = {},
    onRemoveHardcoverToken: () -> Unit = {},
    onHardcoverMetadata: (Boolean) -> Unit = {},
    onClearHardcoverTokenError: () -> Unit = {},
    onMetadataRegion: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    nowMs: Long = 0L,
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ServerSettingsPage(
            state = state,
            onServerName = onServerName,
            onRemoteUrl = onRemoteUrl,
            onHoldNewBooks = onHoldNewBooks,
            onPushNotifications = onPushNotifications,
            onSetRatingSourceEnabled = onSetRatingSourceEnabled,
            onSaveHardcoverToken = onSaveHardcoverToken,
            onRemoveHardcoverToken = onRemoveHardcoverToken,
            onHardcoverMetadata = onHardcoverMetadata,
            onClearHardcoverTokenError = onClearHardcoverTokenError,
            onMetadataRegion = onMetadataRegion,
            onSave = onSave,
            onClearError = onClearError,
            onRetry = onRetry,
            onOpenAdmin = onOpenAdmin,
            nowMs = nowMs,
        )
    }
    return host
}

private fun input(
    host: HTMLElement,
    id: String,
) = host.querySelector("#$id") as HTMLInputElement

private fun switches(host: HTMLElement) = host.querySelectorAll(".srv-toggle .sw-in").asList().filterIsInstance<HTMLInputElement>()

private fun saveButton(host: HTMLElement) = host.querySelector(".edit-actions button[type=submit]") as HTMLElement

private fun buttonLabelled(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun healthStatus(
    source: ExternalRatingSource = ExternalRatingSource.HARDCOVER,
    lastFetchedAt: Long? = null,
    lastError: String? = null,
    pausedUntil: Long? = null,
    unavailable: RatingSourceUnavailable? = null,
    connectionUsername: String? = null,
) = RatingSourceStatus(
    source = source,
    enabled = true,
    lastFetchedAt = lastFetchedAt,
    lastError = lastError,
    pausedUntil = pausedUntil,
    unavailable = unavailable,
    connectionUsername = connectionUsername,
)

/** The one rating-source row's lines under its name, top to bottom. */
private fun healthLines(host: HTMLElement): List<String> =
    host.querySelectorAll(".srv-toggle-d > *").asList().map { it.textContent.orEmpty() }

/** A week after the page's `nowMs` — a pause that has not ended. */
private const val IN_A_WEEK_MS = 1_800_000_000_000L
private const val NOW_MS = IN_A_WEEK_MS - 7L * 24 * 60 * 60 * 1000

/**
 * Server settings.
 *
 * What these pin: the two text fields batch behind Save and the two switches do not, which is the
 * one thing about this screen a reader can get wrong; a switch that fails reverts in the ViewModel,
 * so the banner here is the only thing that says the flick did not take; and the remote URL says
 * what it is for, because an empty one silently sends invites nobody can follow.
 */
class ServerSettingsPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the fields show what the server is called and where it is reached") {
            val host = page(readyServerSettings(serverName = "Kit", remoteUrl = "https://books.example.com"))

            input(host, "srv-name").value shouldBe "Kit"
            input(host, "srv-remote-url").value shouldBe "https://books.example.com"
        }

        test("each field reports its own changes") {
            val seen = mutableMapOf<String, String>()
            val host =
                page(
                    readyServerSettings(),
                    onServerName = { seen["name"] = it },
                    onRemoteUrl = { seen["url"] = it },
                )

            listOf("srv-name" to "one", "srv-remote-url" to "two").forEach { (id, typed) ->
                val field = input(host, id)
                field.value = typed
                field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            }
            awaitFrame()

            seen shouldBe mapOf("name" to "one", "url" to "two")
        }

        test("the remote URL says what an empty one costs") {
            val host = page(readyServerSettings())

            host.textContent.orEmpty() shouldContain "Invites are sent with this address"
        }

        test("Save is disabled until a field changes") {
            val clean = page(readyServerSettings(isDirty = false))
            val dirty = page(readyServerSettings(isDirty = true))

            saveButton(clean).hasAttribute("disabled") shouldBe true
            saveButton(dirty).hasAttribute("disabled") shouldBe false
        }

        test("Save says so while it is in flight, and cannot be pressed twice") {
            val host = page(readyServerSettings(isDirty = true, isSaving = true))

            saveButton(host).textContent shouldBe "Saving…"
            saveButton(host).hasAttribute("disabled") shouldBe true
        }

        test("submitting the form saves") {
            var saved = 0
            val host = page(readyServerSettings(isDirty = true), onSave = { saved++ })

            saveButton(host).click()
            awaitFrame()

            saved shouldBe 1
        }

        test("the switches show the state they were given") {
            val host = page(readyServerSettings(holdNewBooksForReview = true, pushNotificationsEnabled = false))

            switches(host).map { it.hasAttribute("checked") } shouldContainExactly listOf(true, false)
        }

        test("each switch writes its own setting") {
            val flicked = mutableListOf<String>()
            val host =
                page(
                    readyServerSettings(),
                    onHoldNewBooks = { flicked += "hold=$it" },
                    onPushNotifications = { flicked += "push=$it" },
                )

            // Distinct starting values, so a callback wired to the wrong switch cannot read as
            // right: hold starts off and is turned on, push starts on and is turned off.
            switches(host)[0].click()
            switches(host)[1].click()
            awaitFrame()

            flicked shouldContainExactly listOf("hold=true", "push=false")
        }

        // ⛔ The switches are NOT inside the form and NOT under Save — each writes on the flick.
        // Putting them in would make Save appear to govern them, and Enter in a text field would
        // then submit a form containing controls that had already written themselves.
        test("the switches sit outside the form the Save button belongs to") {
            val host = page(readyServerSettings())

            val form = host.querySelector("form").shouldNotBeNull()
            form.querySelectorAll(".srv-toggle").length shouldBe 0
            host.querySelectorAll(".srv-toggle").length shouldBe 2
        }

        test("the page says the switches need no saving") {
            val host = page(readyServerSettings())

            host.textContent.orEmpty() shouldContain "take effect the moment you flick them"
        }

        // A failed flick has already reverted itself in the ViewModel by the time this renders, so
        // without the banner the switch just springs back and nothing says why.
        test("a failed write is reported, announced, and can be dismissed") {
            var cleared = 0
            val host =
                page(
                    readyServerSettings(error = InternalError(debugInfo = "boom")),
                    onClearError = { cleared++ },
                )

            val alert = host.querySelector(".srv-err-t").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            (host.querySelector(".srv-err-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        test("nothing has failed until something does") {
            val host = page(readyServerSettings())

            host.querySelector(".srv-err").shouldBeNull()
        }

        test("a loading page draws a skeleton rather than empty fields") {
            val host = page(AdminSettingsUiState.Loading)

            host.querySelector(".srv-skel").shouldNotBeNull()
            host.querySelector("form").shouldBeNull()
        }

        test("settings that cannot be loaded explain themselves and offer a retry that fires") {
            var retries = 0
            val host = page(AdminSettingsUiState.Error(InternalError(debugInfo = "boom")), onRetry = { retries++ })

            host.querySelector("form").shouldBeNull()
            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            retries shouldBe 1
        }

        test("the breadcrumb leads back to Admin") {
            var back = 0
            val host = page(readyServerSettings(), onOpenAdmin = { back++ })

            (host.querySelector(".crumb a") as HTMLElement).click()
            awaitFrame()

            back shouldBe 1
        }

        test("no rating sources draws no rating-sources section") {
            val host = page(readyServerSettings(ratingSources = emptyList()))

            host.textContent.orEmpty() shouldNotContain "Rating sources"
            // Only the two server-wide switches — see the count pinned two tests up.
            switches(host).size shouldBe 2
        }

        test("one row per rating source: its name, its switch, and its health") {
            val host =
                page(
                    readyServerSettings(
                        ratingSources =
                            listOf(
                                RatingSourceStatus(
                                    source = ExternalRatingSource.AUDIBLE,
                                    enabled = true,
                                    lastFetchedAt = null,
                                    lastError = null,
                                ),
                                RatingSourceStatus(
                                    source = ExternalRatingSource.GOODREADS,
                                    enabled = false,
                                    lastFetchedAt = 1_000L,
                                    lastError = "rate limited",
                                ),
                            ),
                    ),
                    nowMs = 2_000L,
                )

            val text = host.textContent.orEmpty()
            text shouldContain "Rating sources"
            text shouldContain "Audible"
            text shouldContain "Not fetched yet"
            text shouldContain "Goodreads"
            // ⛔ A failed attempt takes priority over a stale success timestamp — see
            // [ratingSourceHealth]: the row says why the fetch is unreliable, not merely when.
            text shouldContain "Last attempt failed: rate limited"

            // The two server-wide switches, plus one per rating source.
            switches(host).size shouldBe 4
            switches(host).map { it.hasAttribute("checked") } shouldContainExactly
                listOf(false, true, true, false)
        }

        test("each rating-source switch reports which source flicked, and to what") {
            val flicked = mutableListOf<Pair<ExternalRatingSource, Boolean>>()
            val host =
                page(
                    readyServerSettings(
                        ratingSources =
                            listOf(
                                RatingSourceStatus(ExternalRatingSource.AUDIBLE, enabled = true, lastFetchedAt = null, lastError = null),
                            ),
                    ),
                    onSetRatingSourceEnabled = { source, enabled -> flicked += source to enabled },
                )

            // The two server-wide switches come first — the rating source is the third.
            switches(host)[2].click()
            awaitFrame()

            flicked shouldContainExactly listOf(ExternalRatingSource.AUDIBLE to false)
        }

        // --- Health lines, in priority order: the same states and words as Android ---

        fun oneSource(status: RatingSourceStatus): HTMLElement = page(readyServerSettings(ratingSources = listOf(status)), nowMs = NOW_MS)

        test("a source the server has not set up says so, above a pause and an error") {
            val host =
                oneSource(
                    healthStatus(
                        unavailable = RatingSourceUnavailable.NOT_CONFIGURED,
                        pausedUntil = IN_A_WEEK_MS,
                        lastError = "Timed out",
                    ),
                )

            healthLines(host) shouldContainExactly listOf("Not set up on this server")
        }

        test("a source waiting for a Hardcover connection asks for one") {
            val host = oneSource(healthStatus(unavailable = RatingSourceUnavailable.NO_CONNECTION, lastFetchedAt = 1L))

            healthLines(host) shouldContainExactly listOf("Add a Hardcover API token or connect an account to enable")
        }

        test("a reason from a newer server still reads as unavailable") {
            val host = oneSource(healthStatus(unavailable = RatingSourceUnavailable.UNKNOWN))

            healthLines(host) shouldContainExactly listOf("Unavailable on this server")
        }

        test("an unavailable source's switch stays operable") {
            val host = oneSource(healthStatus(unavailable = RatingSourceUnavailable.NO_CONNECTION))

            // Turning an unavailable source off is still meaningful — only a save in flight locks it.
            switches(host)[2].hasAttribute("disabled") shouldBe false
        }

        test("a paused source says until when, and why") {
            val host =
                oneSource(
                    healthStatus(source = ExternalRatingSource.GOODREADS, pausedUntil = IN_A_WEEK_MS, lastError = "Timed out"),
                )

            healthLines(host) shouldContainExactly listOf("Paused until ${formatDateLong(IN_A_WEEK_MS)}: Timed out")
        }

        test("a paused source with no recorded reason still says until when") {
            val host = oneSource(healthStatus(source = ExternalRatingSource.GOODREADS, pausedUntil = IN_A_WEEK_MS))

            healthLines(host) shouldContainExactly listOf("Paused until ${formatDateLong(IN_A_WEEK_MS)}")
        }

        test("a failing source that is not paused reports its last error") {
            val host =
                oneSource(healthStatus(source = ExternalRatingSource.AUDIBLE, lastError = "Timed out", lastFetchedAt = 1L))

            healthLines(host) shouldContainExactly listOf("Last attempt failed: Timed out")
        }

        test("a healthy source says when it last fetched") {
            val host = oneSource(healthStatus(source = ExternalRatingSource.AUDIBLE, lastFetchedAt = NOW_MS))

            healthLines(host) shouldContainExactly listOf("Last fetched just now")
        }

        test("a source that never ran says so") {
            val host = oneSource(healthStatus(source = ExternalRatingSource.AUDIBLE))

            healthLines(host) shouldContainExactly listOf("Not fetched yet")
        }

        test("Hardcover names whose account it uses on a second line") {
            val host = oneSource(healthStatus(lastFetchedAt = NOW_MS, connectionUsername = "simon"))

            healthLines(host) shouldContainExactly
                listOf("Last fetched just now", "Using simon's Hardcover account")
        }

        test("with no token, the Hardcover panel takes one and sends it once") {
            val sent = mutableListOf<String>()
            val host = page(readyServerSettings().copy(hardcoverSource = HardcoverSourceStatus()), onSaveHardcoverToken = { sent += it })
            awaitFrame()

            val field = input(host, "hc-token")
            field.type shouldBe "password"
            field.getAttribute("autocomplete") shouldBe "off"
            field.value = "hc_web_test_token"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            buttonLabelled(host, "Save token")!!.click()

            sent shouldBe listOf("hc_web_test_token")
        }

        test("a saved token is described by its owner only, and Remove asks first") {
            var removed = 0
            val host =
                page(
                    readyServerSettings().copy(
                        hardcoverSource = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L)),
                    ),
                    onRemoveHardcoverToken = { removed++ },
                )
            awaitFrame()

            (host.textContent ?: "") shouldContain "Set · belongs to @simon"
            host.querySelector("#hc-token").shouldBeNull()
            buttonLabelled(host, "Remove API token")!!.click()
            awaitFrame()
            removed shouldBe 0
            (host.textContent ?: "") shouldContain "Remove the API token?"
        }

        test("a refused token is explained beside the field, and a rejected one asks to be replaced") {
            val refused =
                page(
                    readyServerSettings().copy(
                        hardcoverSource = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Rejected("simon")),
                        hardcoverTokenSave = HardcoverTokenSave.Refused(HardcoverError.TokenRejected()),
                    ),
                )
            awaitFrame()

            (refused.textContent ?: "") shouldContain "Hardcover rejected this token — replace it"
            (refused.textContent ?: "") shouldContain "Hardcover didn't accept that token. Check it and try again."
        }

        test("while Hardcover checks the token, Save says so and can't be pressed") {
            val sent = mutableListOf<String>()
            val host =
                page(
                    readyServerSettings().copy(hardcoverSource = HardcoverSourceStatus(), hardcoverTokenSave = HardcoverTokenSave.Busy),
                    onSaveHardcoverToken = { sent += it },
                )
            awaitFrame()

            // Unavailable, never `disabled`: that would drop the focus of the press that started it.
            val button = buttonLabelled(host, "Checking with Hardcover…").shouldNotBeNull()
            button.getAttribute("aria-disabled") shouldBe "true"
            button.click()
            sent shouldBe emptyList()
        }

        test("the Hardcover metadata switch reflects the setting and flips it") {
            val flips = mutableListOf<Boolean>()
            val host = page(readyServerSettings().copy(hardcoverSource = HardcoverSourceStatus()), onHardcoverMetadata = { flips += it })
            awaitFrame()

            val metadataSwitch = switches(host).last()
            metadataSwitch.checked shouldBe true
            metadataSwitch.click()

            flips shouldBe listOf(false)
        }

        test("no Hardcover panel until the section has loaded") {
            val host = page(readyServerSettings())
            awaitFrame()

            host.querySelector("#hc-token").shouldBeNull()
            (host.textContent ?: "") shouldNotContain "Hardcover metadata"
        }

        test("Store region shows the library's store as one labelled menu, and a choice saves at once") {
            var chosen: String? = null
            val host = page(readyServerSettings(metadataRegion = "uk"), onMetadataRegion = { chosen = it })
            awaitFrame()

            val select = host.querySelector("#srv-store-region") as HTMLSelectElement
            select.value shouldBe "uk"
            host.querySelector("label[for='srv-store-region']")?.textContent shouldBe "Store region"
            host.textContent.shouldNotBeNull() shouldContain "Each search can still pick another store."

            select.value = "au"
            select.dispatchEvent(Event("change"))
            awaitFrame()

            chosen shouldBe "au"
        }
    })
