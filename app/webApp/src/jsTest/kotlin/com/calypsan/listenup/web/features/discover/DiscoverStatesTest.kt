package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

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

        test("only you, as an admin: the invitation with Invite someone, which opens the invite page") {
            var invited = 0
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening = readyListening(emptyList()),
                        communitySize = CommunitySize.JustYou,
                        onInvite = { invited++ },
                    )
                }
            host.querySelector(".disc-invite-title")!!.textContent shouldBe "It's just you here so far"
            host.textContent.orEmpty() shouldContain
                "When other people join, you'll see what they're listening to, the shelves they share and what they finish."
            val button = host.querySelectorAll(".disc-invite button").asList().single() as HTMLElement
            button.textContent shouldBe "Invite someone"
            button.click()
            invited shouldBe 1
        }

        test("only you, as a member: the invitation with no button") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening = readyListening(emptyList()),
                        communitySize = CommunitySize.JustYou,
                        viewer = DiscoverViewer("me", "Simon", canInvite = false),
                    )
                }
            host.querySelector(".disc-invite-title")!!.textContent shouldBe "It's just you here so far"
            host.querySelectorAll(".disc-invite button").length shouldBe 0
        }

        test("before the roster has synced, an empty Listening now is a skeleton, not a claim") {
            val host =
                mounts.mount {
                    discoverPage(currentlyListening = readyListening(emptyList()), communitySize = CommunitySize.Unknown)
                }
            host.querySelectorAll(".disc-a-listen .skel").length shouldBe 1
            host.textContent.orEmpty() shouldNotContain "just you"
            host.textContent.orEmpty() shouldNotContain "Nobody's listening"
        }

        test("others on the server but nobody listening says so") {
            val host = mounts.mount { discoverPage(currentlyListening = readyListening(emptyList())) }
            host.querySelector(".disc-a-listen .disc-quiet")!!.textContent shouldBe
                "Nobody's listening right now. When someone starts, they'll show up here."
        }

        test("a stale roster says As of, politely") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening =
                            CurrentlyListeningUiState.Ready(
                                listOf(listenerOf("u1", live = true)),
                                observedAtMs = DISCOVER_NOW_MS - 7_200_000,
                                isStale = true,
                            ),
                    )
                }
            val asOf = host.querySelector(".disc-as-of") as HTMLElement
            asOf.textContent.orEmpty() shouldStartWith "As of "
            asOf.getAttribute("role") shouldBe "status"
        }

        test("only you: the period chips stay, the category goes, Just you so far closes the board") {
            val host =
                mounts.mount {
                    discoverPage(leaderboard = board(leaders(count = 1, youAt = 1)), communitySize = CommunitySize.JustYou)
                }
            host.querySelectorAll(".disc-lb-card [role=radiogroup]").length shouldBe 1
            host.textContent.orEmpty() shouldContain "Just you so far. The board fills in as people join."
        }

        test("a period nobody listened in keeps both controls and says so") {
            val host = mounts.mount { discoverPage(leaderboard = LeaderboardUiState.Empty) }
            host.querySelectorAll(".disc-lb-card [role=radiogroup]").length shouldBe 2
            host.textContent.orEmpty() shouldContain "Nobody listened in this period yet."
        }

        test("an empty board before the roster has synced is a skeleton, never a claim") {
            val host =
                mounts.mount { discoverPage(leaderboard = LeaderboardUiState.Empty, communitySize = CommunitySize.Unknown) }
            host.querySelectorAll(".disc-lb-card .disc-skel-rows").length shouldBe 1
            host.textContent.orEmpty() shouldNotContain "Nobody listened"
        }

        test("started everything offers the Library, and Shuffle steps aside") {
            var browsed = 0
            val host =
                mounts.mount { discoverPage(books = DiscoverBooksUiState.AllStarted, onBrowseLibrary = { browsed++ }) }
            host.textContent.orEmpty() shouldContain "You've started every book here."
            host.textContent.orEmpty() shouldNotContain "Shuffle"
            (host.querySelector(".disc-a-new .disc-browse") as HTMLElement).click()
            browsed shouldBe 1
        }

        test("shelves offline need a connection; other failures don't say so") {
            val offline =
                mounts.mount { discoverPage(shelves = DiscoverShelvesUiState.Error(TransportError.NetworkUnavailable())) }
            offline.querySelector(".disc-a-shelves .disc-error")!!.textContent shouldBe "Shelves by others need a connection"
            val broken = mounts.mount { discoverPage(shelves = DiscoverShelvesUiState.Error(UnexpectedClientError())) }
            broken.querySelector(".disc-a-shelves .disc-error")!!.textContent shouldBe "Couldn't load shelves by others"
        }

        test("no public shelves says where they will show up") {
            val host = mounts.mount { discoverPage(shelves = DiscoverShelvesUiState.Ready(emptyList())) }
            host.querySelector(".disc-a-shelves .disc-quiet")!!.textContent shouldBe "Shelves people make public show up here."
        }

        test("tied leaders share the number and both wear gold") {
            val host = mounts.mount { discoverPage(leaderboard = board(tiedAtTheTop())) }
            host.querySelectorAll(".disc-rank.medal-1").length shouldBe 2
        }
    })
