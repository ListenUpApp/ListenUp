package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLAnchorElement

/** The hero's social line (spec §2.10): one link to the Readers panel, the avatars hidden, two names a clause. */
class HeroSocialLineTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun refs(vararg names: String) = names.map { ReaderRef(it.lowercase(), it) }

        test("one link to #readers, avatars hidden, the sentence as its text") {
            val root =
                mounts.mount {
                    HeroSocialLine(
                        ReadersSummary(
                            listening = refs("Maya", "Priya"),
                            finished = refs("Tom"),
                            avatars = refs("Maya", "Priya", "Tom"),
                            othersCount = 0,
                        ),
                    )
                }
            val link = root.querySelector("a.bd-social") as HTMLAnchorElement
            link.getAttribute("href") shouldBe "#readers"
            link.querySelector(".bd-social-avatars")!!.getAttribute("aria-hidden") shouldBe "true"
            link.querySelector(".bd-social-text")!!.textContent shouldBe "Maya and Priya are listening · Tom finished it"
        }

        test("nobody but you renders nothing") {
            val root = mounts.mount { HeroSocialLine(ReadersSummary(emptyList(), emptyList(), emptyList(), 0)) }
            root.querySelector("a.bd-social") shouldBe null
        }

        test("each clause names two and counts the rest") {
            socialSentence(ReadersSummary(refs("Maya", "Priya", "A", "B", "C"), emptyList(), refs("Maya"), 3)) shouldBe
                "Maya, Priya and 3 others are listening"
            socialSentence(ReadersSummary(refs("Maya", "Priya", "A"), emptyList(), refs("Maya"), 1)) shouldBe
                "Maya, Priya and 1 other are listening"
            socialSentence(ReadersSummary(emptyList(), refs("Tom"), refs("Tom"), 0)) shouldBe "Tom finished it"
            socialSentence(ReadersSummary(refs("Maya"), emptyList(), refs("Maya"), 0)) shouldBe "Maya is listening"
        }
    
        test("full names read as first names, in the sentence and in bold") {
            val full = refs("Maya Chen", "Priya Nair")
            val summary = ReadersSummary(full, refs("Tom Okafor"), full + refs("Tom Okafor"), 0)
            socialSentence(summary) shouldBe "Maya and Priya are listening · Tom finished it"
            val root = mounts.mount { HeroSocialLine(summary) }
            val bold = root.querySelectorAll(".bd-social-text b")
            (0 until bold.length).map { bold.item(it)!!.textContent } shouldBe listOf("Maya", "Priya", "Tom")
        }
    })
