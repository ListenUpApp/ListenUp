package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement

/**
 * Spec §3.8 on the web: every Discover section keeps its place while loading, says plainly when it
 * failed (a status line, so a screen reader hears it) and offers its own Try again.
 */
class DiscoverStatesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("a failed section is a status line and its own Try again") {
            var retried = 0
            val host =
                mounts.mount {
                    discoverPage(
                        recentlyAdded = RecentlyAddedUiState.Error(UnexpectedClientError()),
                        onRetryRecent = { retried++ },
                    )
                }
            val line = host.querySelector(".disc-a-recent .disc-error") as HTMLElement
            line.textContent shouldBe "Couldn't load recently added"
            line.getAttribute("role") shouldBe "status"
            (host.querySelector(".disc-a-recent .disc-retry") as HTMLElement).click()
            retried shouldBe 1
        }

        test("loading sections stand in with the shapes they will take") {
            val host = mounts.mount { discoverPage() }
            host.querySelectorAll(".disc-a-new .disc-skel-rail").length shouldBe 1
            host.querySelectorAll(".disc-aside .disc-skel-rows").length shouldBe 2
        }
    })
