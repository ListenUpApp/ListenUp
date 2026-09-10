package com.calypsan.listenup.web.features.contributormetadata

import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorSearchLoadState
import com.calypsan.listenup.core.ContributorId
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
import org.w3c.dom.asList
import org.w3c.dom.events.Event

private val hosts = mutableListOf<HTMLElement>()

internal fun contributorProfile(
    asin: String = "B000APZ21C",
    name: String = "Patrick Rothfuss",
    description: String? = null,
    imageUrl: String? = null,
) = MetadataContributorProfile(
    asin = asin,
    name = name,
    sortName = null,
    description = description,
    imageUrl = imageUrl,
    birthDate = null,
    deathDate = null,
    website = null,
)

internal fun localContributor(
    id: String = "c1",
    name: String = "Pat Rothfuss",
    description: String? = null,
    imagePath: String? = null,
) = Contributor(
    id = ContributorId(id),
    name = name,
    description = description,
    imagePath = imagePath,
)

internal fun contributorSearchState(
    query: String = "Patrick Rothfuss",
    loadState: ContributorSearchLoadState = ContributorSearchLoadState.Idle,
    region: MetadataLocale = MetadataLocale.DEFAULT,
    current: Contributor? = localContributor(),
) = ContributorMetadataUiState.Search(
    region = region,
    context = ContributorContext(contributorId = "c1", current = current),
    query = query,
    loadState = loadState,
)

internal fun contributorPreviewState(
    loadState: ContributorPreviewLoadState,
    region: MetadataLocale = MetadataLocale.DEFAULT,
    current: Contributor? = localContributor(),
) = ContributorMetadataUiState.Preview(
    region = region,
    context = ContributorContext(contributorId = "c1", current = current),
    query = "Patrick Rothfuss",
    searchResults = emptyList(),
    match = MetadataContributorHit(asin = "B000APZ21C", name = "Patrick Rothfuss"),
    loadState = loadState,
)

private fun page(
    state: ContributorMetadataUiState,
    onQuery: (String) -> Unit = {},
    onRegion: (MetadataLocale) -> Unit = {},
    onSearch: () -> Unit = {},
    onSelectCandidate: (MetadataContributorHit) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onApply: () -> Unit = {},
    onLeave: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ContributorMetadataPage(
            state = state,
            onQuery = onQuery,
            onRegion = onRegion,
            onSearch = onSearch,
            onSelectCandidate = onSelectCandidate,
            onClearSelection = onClearSelection,
            onApply = onApply,
            onLeave = onLeave,
        )
    }
    return host
}

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * Match contributor — the Audible wizard over one person.
 *
 * What these pin: the search says who is being matched and offers the candidates; the preview shows
 * what Apply would actually change; an empty regional catalogue is an honest miss with a way out;
 * and — the one this page is most careful about — **the matched name is identification, never a
 * before-and-after**, because the server's applier writes only `asin`, `description` and
 * `imagePath`. A rename is not on offer, so the page must not look like it is.
 */
class ContributorMetadataPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the search phase says who is being matched") {
            val host = page(contributorSearchState(current = localContributor(name = "Pat Rothfuss")))

            host.querySelector(".cmx-ctx")?.textContent shouldContain "Pat Rothfuss"
        }

        test("the query is seeded, and typing reports the change") {
            val typed = mutableListOf<String>()
            val host = page(contributorSearchState(query = "Patrick Rothfuss"), onQuery = { typed += it })

            val field = host.querySelector("#cmx-query") as HTMLInputElement
            field.value shouldBe "Patrick Rothfuss"
            field.value = "Brandon Sanderson"
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            typed shouldContainExactly listOf("Brandon Sanderson")
        }

        test("a search cannot be started twice, or on an empty query") {
            button(page(contributorSearchState(loadState = ContributorSearchLoadState.InFlight)), "Searching…")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
            button(page(contributorSearchState(query = "")), "Search Audible")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("submitting the form searches") {
            val searches = mutableListOf<Unit>()
            val host = page(contributorSearchState(), onSearch = { searches += Unit })

            button(host, "Search Audible").shouldNotBeNull().click()
            awaitFrame()

            searches.size shouldBe 1
        }

        test("a failed search is announced") {
            val host = page(contributorSearchState(loadState = ContributorSearchLoadState.Failed("Search timed out.")))

            val alert = host.querySelector(".cmx-err").shouldNotBeNull()
            alert.getAttribute("role") shouldBe "alert"
            alert.textContent shouldBe "Search timed out."
        }

        test("no candidates says so") {
            val host = page(contributorSearchState(loadState = ContributorSearchLoadState.Loaded(emptyList())))

            host.querySelector(".cmx-none")?.textContent shouldBe "No contributors match that search."
        }

        test("every candidate is offered by name and ASIN, and picking one reports it") {
            val picked = mutableListOf<String>()
            val host =
                page(
                    contributorSearchState(
                        loadState =
                            ContributorSearchLoadState.Loaded(
                                listOf(
                                    MetadataContributorHit(asin = "B1", name = "Patrick Rothfuss"),
                                    MetadataContributorHit(asin = "B2", name = "Patrick Rothfuss"),
                                ),
                            ),
                    ),
                    onSelectCandidate = { picked += it.asin },
                )

            val hits = host.querySelectorAll(".cmx-hit").asList().filterIsInstance<HTMLButtonElement>()
            hits.size shouldBe 2
            // ⛔ The ASIN is on the row. Two people can share a name, and the ASIN is the only thing
            // that tells the reader which of them they are about to take a biography from.
            hits[1].textContent.shouldNotBeNull() shouldContain "B2"

            hits[1].click()
            awaitFrame()

            picked shouldContainExactly listOf("B2")
        }

        // MARK: preview

        test("a loading preview draws nothing it does not know yet") {
            val host = page(contributorPreviewState(ContributorPreviewLoadState.Loading))

            host.querySelector(".cmx-skel").shouldNotBeNull()
            host.querySelector(".cmx-cmp").shouldBeNull()
        }

        test("a failed preview is announced, and offers another match") {
            val back = mutableListOf<Unit>()
            val host =
                page(
                    contributorPreviewState(ContributorPreviewLoadState.Failed("Loading the profile timed out.")),
                    onClearSelection = { back += Unit },
                )

            host.querySelector(".cmx-err")?.textContent shouldBe "Loading the profile timed out."
            button(host, "Change match").shouldNotBeNull().click()
            awaitFrame()

            back.size shouldBe 1
        }

        // ⛔ An honest miss, not a blank preview above a live Apply. Audnexus answers a cross-region
        // fetch with an empty HTTP-200 shell and the server refuses to apply one, so the page names
        // the empty catalogue and offers the switch that fixes it.
        test("an empty regional catalogue says which one, and offers no Apply") {
            val host =
                page(contributorPreviewState(ContributorPreviewLoadState.Missing, region = MetadataLocale("de")))

            host.querySelector(".cmx-empty")?.textContent shouldContain "Germany"
            button(host, "Apply").shouldBeNull()
            button(host, "United Kingdom").shouldNotBeNull()
        }

        // ⛔ THE rule of this page. The server's applier writes `asin`, `description` and
        // `imagePath` — never the name. A "Current name → Audible name" row would promise a rename
        // that does not happen, so the matched name appears once, as identification.
        test("the matched name identifies the person and is never shown as a rename") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(name = "Patrick Rothfuss", description = "Wrote Kvothe."),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(name = "Pat Rothfuss"),
                    ),
                )

            host.querySelector(".cmx-who")?.textContent shouldContain "Patrick Rothfuss"
            // The local name appears nowhere on the preview: it is not changing, so showing it
            // beside the incoming one would read as a before-and-after.
            host.querySelector(".cmx-who")?.textContent.shouldNotBeNull() shouldNotContain "Pat Rothfuss"
            host.querySelectorAll(".cmx-cmp-l").asList().map { it.textContent } shouldContainExactly
                listOf("Photo", "Biography")
        }

        test("the biography is compared, current beside incoming") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(description = "Wrote Kvothe."),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(description = "A writer."),
                    ),
                )

            val bios = host.querySelectorAll(".cmx-bio").asList().map { it.textContent }
            bios shouldContainExactly listOf("A writer.", "Wrote Kvothe.")
        }

        // ⛔ The server never blanks an existing value with a missing incoming one, so the "after"
        // side has to say "unchanged" rather than show an empty box that reads as a deletion.
        test("a missing incoming biography says the current one is kept") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(description = null),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(description = "A writer."),
                    ),
                )

            host
                .querySelectorAll(".cmx-bio")
                .asList()[1]
                .textContent
                .shouldNotBeNull() shouldContain "Unchanged"
        }

        test("a contributor with no biography yet shows a placeholder, not a blank") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(description = "Wrote Kvothe."),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(description = null),
                    ),
                )

            host.querySelectorAll(".cmx-bio").asList()[0].textContent shouldBe "—"
        }

        test("the current photo is served from this server, and the incoming one leaks no referrer") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(imageUrl = "https://m.media-amazon.com/p.jpg"),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(imagePath = "contributors/c1.jpg"),
                    ),
                )

            val photos = host.querySelectorAll(".cmx-photos img").asList().filterIsInstance<HTMLElement>()
            photos[0].getAttribute("src") shouldBe "/api/v1/contributors/c1/photo"
            photos[1].getAttribute("referrerpolicy") shouldBe "no-referrer"
        }

        test("a missing incoming photo says the current one is kept") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(imageUrl = null),
                            isApplying = false,
                            applyError = null,
                        ),
                        current = localContributor(imagePath = "contributors/c1.jpg"),
                    ),
                )

            host.querySelector(".cmx-photo-none")?.textContent shouldBe "Unchanged"
        }

        test("Apply reports the press, and says so while it is in flight") {
            val applied = mutableListOf<Unit>()
            val ready =
                ContributorPreviewLoadState.Ready(
                    profile = contributorProfile(description = "Wrote Kvothe."),
                    isApplying = false,
                    applyError = null,
                )
            val host = page(contributorPreviewState(ready), onApply = { applied += Unit })

            button(host, "Apply").shouldNotBeNull().click()
            awaitFrame()
            applied.size shouldBe 1

            button(page(contributorPreviewState(ready.copy(isApplying = true))), "Applying…")
                .shouldNotBeNull()
                .hasAttribute("disabled") shouldBe true
        }

        test("a failed apply is announced, and the preview stays put") {
            val host =
                page(
                    contributorPreviewState(
                        ContributorPreviewLoadState.Ready(
                            profile = contributorProfile(description = "Wrote Kvothe."),
                            isApplying = false,
                            applyError = "The server refused that.",
                        ),
                    ),
                )

            host.querySelector(".cmx-err")?.textContent shouldBe "The server refused that."
            // The comparison is still there — a failed apply costs the reader nothing.
            host.querySelectorAll(".cmx-cmp").asList().size shouldBe 2
        }

        test("leaving the wizard reports it") {
            val left = mutableListOf<Unit>()
            val host = page(contributorSearchState(), onLeave = { left += Unit })

            button(host, "Back").shouldNotBeNull().click()
            awaitFrame()

            left.size shouldBe 1
        }
    })
