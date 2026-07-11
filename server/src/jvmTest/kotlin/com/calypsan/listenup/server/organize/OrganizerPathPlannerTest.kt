package com.calypsan.listenup.server.organize

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Decision-table pin for [OrganizerPathPlanner.planFor]: every preset × knob combination against
 * a small set of representative [BookOrganizeFacts]. This is the load-bearing spec of the
 * organizer's naming behavior — changing an expected row here is a naming-policy change, not a
 * refactor.
 */
class OrganizerPathPlannerTest :
    FunSpec({
        val stormlight =
            BookOrganizeFacts(
                title = "The Way of Kings",
                subtitle = null,
                primaryAuthor = "Brandon Sanderson",
                seriesName = "Stormlight Archive",
                seriesSequence = "1",
                isMultiFile = true,
            )
        val edgedancer = stormlight.copy(title = "Edgedancer", seriesSequence = "1.5")
        val standalone = stormlight.copy(seriesName = null, seriesSequence = null)
        val noAuthor = stormlight.copy(primaryAuthor = null)

        test("AUTHOR_SERIES_TITLE + BOOK_N_DASH + FIRST_LAST") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.BOOK_N_DASH,
                    authorForm = AuthorForm.FIRST_LAST,
                )
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe
                "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
        }

        test("AUTHOR_SERIES_TITLE with no series omits the series folder and prefix") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.BOOK_N_DASH,
                    authorForm = AuthorForm.FIRST_LAST,
                )
            OrganizerPathPlanner.planFor(standalone, settings) shouldBe
                "Brandon Sanderson/The Way of Kings"
        }

        test("N_DASH prefix with a decimal series sequence") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.N_DASH,
                    authorForm = AuthorForm.FIRST_LAST,
                )
            OrganizerPathPlanner.planFor(edgedancer, settings) shouldBe
                "Brandon Sanderson/Stormlight Archive/1.5 - Edgedancer"
        }

        test("BRACKET_N prefix") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.BRACKET_N,
                    authorForm = AuthorForm.FIRST_LAST,
                )
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe
                "Brandon Sanderson/Stormlight Archive/[1] The Way of Kings"
        }

        test("NONE prefix drops the sequence entirely") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.NONE,
                    authorForm = AuthorForm.FIRST_LAST,
                )
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe
                "Brandon Sanderson/Stormlight Archive/The Way of Kings"
        }

        test("LAST_FIRST author form") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.BOOK_N_DASH,
                    authorForm = AuthorForm.LAST_FIRST,
                )
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe
                "Sanderson, Brandon/Stormlight Archive/Book 1 - The Way of Kings"
        }

        test("LAST_FIRST with a single-word author name is left unchanged") {
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_TITLE,
                    authorForm = AuthorForm.LAST_FIRST,
                )
            OrganizerPathPlanner.planFor(stormlight.copy(primaryAuthor = "Cher"), settings) shouldBe
                "Cher/The Way of Kings"
        }

        test("AUTHOR_TITLE ignores series entirely") {
            val settings = OrganizerSettings(preset = StructurePreset.AUTHOR_TITLE)
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe
                "Brandon Sanderson/The Way of Kings"
        }

        test("FLAT_TITLE is just the sanitized title, no author or series prefix") {
            val settings = OrganizerSettings(preset = StructurePreset.FLAT_TITLE)
            OrganizerPathPlanner.planFor(stormlight, settings) shouldBe "The Way of Kings"
        }

        test("no author falls back to the Unknown Author constant") {
            val settings = OrganizerSettings(preset = StructurePreset.AUTHOR_SERIES_TITLE)
            OrganizerPathPlanner.planFor(noAuthor, settings) shouldBe
                "Unknown Author/Stormlight Archive/Book 1 - The Way of Kings"
        }

        test("blank author also falls back to the Unknown Author constant") {
            val settings = OrganizerSettings(preset = StructurePreset.AUTHOR_TITLE)
            OrganizerPathPlanner.planFor(stormlight.copy(primaryAuthor = "  "), settings) shouldBe
                "Unknown Author/The Way of Kings"
        }

        test("every segment passes through PathSanitizer") {
            val messy =
                stormlight.copy(
                    title = """The <Best> Book?""",
                    primaryAuthor = """Brandon: Sanderson""",
                    seriesName = """Storm/light""",
                )
            val settings =
                OrganizerSettings(
                    preset = StructurePreset.AUTHOR_SERIES_TITLE,
                    seriesPrefix = SeriesPrefixStyle.BOOK_N_DASH,
                )
            OrganizerPathPlanner.planFor(messy, settings) shouldBe
                "Brandon Sanderson/Stormlight/Book 1 - The Best Book"
        }

        test("default settings match the AUTHOR_SERIES_TITLE + BOOK_N_DASH + FIRST_LAST row") {
            OrganizerPathPlanner.planFor(stormlight, OrganizerSettings()) shouldBe
                "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
        }
    })
