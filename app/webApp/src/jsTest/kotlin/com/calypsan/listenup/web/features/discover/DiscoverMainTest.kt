package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** Discover's main column: who is listening, something new, shelves by others, recently added. */
class DiscoverMainTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("Listening now is first, and says how many are live and how many more") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening =
                            listening(
                                listenerOf("u1", live = true),
                                listenerOf("u2", live = true),
                                listenerOf("u3", live = false),
                            ),
                    )
                }
            host.querySelectorAll(".disc-section-h").item(0)!!.textContent shouldBe "Listening now"
            host.querySelector(".disc-sub")!!.textContent shouldBe "2 listening now · 1 more in the last two weeks"
        }

        test("the book link holds the title and is named for the book and the person; the person is a sibling link") {
            var book: String? = null
            var person: String? = null
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening = listening(listenerOf("u1", live = true)),
                        onOpenBook = { book = it },
                        onOpenProfile = { person = it },
                    )
                }
            val links = host.querySelectorAll(".disc-listener a").asList().map { it as HTMLElement }
            links.size shouldBe 2
            links[0].getAttribute("href") shouldBe "/book/b-u1"
            links[0].getAttribute("aria-label") shouldBe "The Institute, Ada is listening now"
            links[0].textContent.orEmpty() shouldContain "The Institute"
            links[0].querySelector("a") shouldBe null
            links[1].getAttribute("href") shouldBe "/profile/u1"
            links[1].querySelector(".uav") shouldNotBe null
            links[0].click()
            links[1].click()
            book shouldBe "b-u1"
            person shouldBe "u1"
        }

        test("Live sits on the art of a live row only, as text") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening =
                            listening(
                                listenerOf("u1", live = true),
                                listenerOf("u2", live = false, lastActiveAt = DISCOVER_NOW_MS - DISCOVER_TWO_DAYS_MS),
                            ),
                    )
                }
            host.querySelectorAll(".disc-live-chip").length shouldBe 1
            host.querySelector(".disc-live-chip")!!.textContent shouldBe "Live"
        }

        test("a listener who stopped is named for when they listened") {
            val host =
                mounts.mount {
                    discoverPage(
                        currentlyListening =
                            listening(listenerOf("u2", live = false, lastActiveAt = DISCOVER_NOW_MS - DISCOVER_TWO_DAYS_MS)),
                    )
                }
            (host.querySelector(".disc-listener-book") as HTMLElement).getAttribute("aria-label") shouldBe
                "The Institute, Ada listened 2 days ago"
        }
    })
