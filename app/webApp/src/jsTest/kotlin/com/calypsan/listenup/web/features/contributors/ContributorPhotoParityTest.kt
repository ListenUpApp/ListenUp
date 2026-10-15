package com.calypsan.listenup.web.features.contributors

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.awaitImagesApplied
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * Spec Q5, a parity stop: Android and iOS showed a contributor's photo on the Contributors list and
 * on their page, while web drew initials only. These pin the photo, its frame, and the monogram that
 * stays the finished avatar for everyone with no photo or a photo that will not load.
 */
class ContributorPhotoParityTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun contributorsPage(people: List<ContributorWithBookCount>): HTMLElement =
            mounts.mount {
                ContributorsPage(
                    state = people,
                    role = ContributorRole.AUTHOR,
                    onSelectFacet = {},
                    onOpenContributor = {},
                    sortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
                    onEvent = {},
                )
            }

        fun detailPage(state: ContributorDetailUiState): HTMLElement =
            mounts.mount {
                ContributorDetailPage(
                    onAdjustPhoto = {},
                    state = state,
                    onOpenLibrary = {},
                    onOpenContributors = {},
                    onOpenBook = {},
                    onConfirmDelete = {},
                    onDismissDeleteError = {},
                )
            }

        // Each "has a photo" case reads the DOM from the first composition: a spec has no server, so
        // the photo itself 404s a moment later and is rightly replaced by the monogram.
        test("the contributors list shows a contributor's photo, framed, not only initials") {
            val host =
                contributorsPage(
                    listOf(
                        contributor(
                            id = "c-weir",
                            name = "Andy Weir",
                            imagePath = "contributors/abc.jpg",
                            imageFrame = ImageFrame(0.5, 0.3, 1.5),
                        ),
                    ),
                )
            val img = (host.querySelector(".contrib-avatar img.framed-img") as? HTMLImageElement).shouldNotBeNull()
            img.getAttribute("src") shouldBe "/api/v1/contributors/c-weir/photo?v=contributors/abc.jpg"
        }

        test("a contributor with no photo keeps the initials and asks for nothing") {
            val host = contributorsPage(listOf(contributor(id = "c-x", name = "Ann Leckie")))
            host.querySelector(".contrib-avatar img").shouldBeNull()
            host.querySelector(".contrib-avatar")!!.textContent shouldBe "AL"
        }

        test("contributor detail shows the photo in the hero") {
            val host = detailPage(readyContributor(imagePath = "contributors/abc.jpg"))
            host.querySelector(".cd-avatar img.framed-img").shouldNotBeNull()
        }

        test("a photo that fails to load falls back to initials, as the 404 is routine") {
            val host = detailPage(readyContributor(imagePath = "contributors/missing.jpg"))
            awaitImagesApplied(host)
            host.querySelector(".cd-avatar img").shouldBeNull()
            host.querySelector(".cd-avatar")!!.textContent shouldBe "SK"
        }
    })
