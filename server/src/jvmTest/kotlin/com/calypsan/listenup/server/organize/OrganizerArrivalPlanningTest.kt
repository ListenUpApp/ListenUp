package com.calypsan.listenup.server.organize

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [OrganizerPathPlanner.planForArrival] — the uploads/new-arrival seam: an arrival ALWAYS lands
 * structured, under the admin's live schema. There is no "leave it alone" reading of a file that
 * does not yet have a home, so there is no fallback branch left to test — only that arrivals and
 * every other caller derive the same path from the same rules.
 */
class OrganizerArrivalPlanningTest :
    FunSpec({
        val facts =
            BookOrganizeFacts(
                title = "The Way of Kings",
                subtitle = null,
                primaryAuthor = "Brandon Sanderson",
                seriesName = "Stormlight Archive",
                seriesSequence = 1.0,
                isMultiFile = true,
            )

        test("arrivals are planned with the admin's schema") {
            val settings = OrganizerSettings(preset = StructurePreset.FLAT_TITLE)
            OrganizerPathPlanner.planForArrival(facts, settings) shouldBe "The Way of Kings"
        }

        test("arrivals fall to the default Author/Series/Title shape when the admin never chose") {
            OrganizerPathPlanner.planForArrival(facts, OrganizerSettings()) shouldBe
                "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
        }
    })
