package com.calypsan.listenup.server.organize

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [OrganizerPathPlanner.planForArrival] — the uploads/new-arrival seam (Phase 4 consumes it):
 * arrivals ALWAYS land structured. An enabled organizer applies the admin's schema; a disabled
 * one falls back to the default schema rather than dumping files unstructured.
 */
class OrganizerArrivalPlanningTest :
    FunSpec({
        val facts =
            BookOrganizeFacts(
                title = "The Way of Kings",
                subtitle = null,
                primaryAuthor = "Brandon Sanderson",
                seriesName = "Stormlight Archive",
                seriesSequence = "1",
                isMultiFile = true,
            )

        test("enabled organizer plans arrivals with the admin's schema") {
            val settings = OrganizerSettings(enabled = true, preset = StructurePreset.FLAT_TITLE)
            OrganizerPathPlanner.planForArrival(facts, settings) shouldBe "The Way of Kings"
        }

        test("disabled organizer still structures arrivals — with the DEFAULT schema, not the disabled one") {
            val disabledFlat = OrganizerSettings(enabled = false, preset = StructurePreset.FLAT_TITLE)
            OrganizerPathPlanner.planForArrival(facts, disabledFlat) shouldBe
                "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
        }
    })
