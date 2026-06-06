package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.ContributorRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun row(
    name: String,
    role: ContributorRole,
) = ParsedContributor(name, role)

class ContributorParserTest :
    FunSpec({
        test("semicolon split with a role suffix on the second person") {
            ContributorParser.parse("Stephen King; Joe Hill - Introduction", ContributorRole.AUTHOR) shouldBe
                listOf(row("Stephen King", ContributorRole.AUTHOR), row("Joe Hill", ContributorRole.INTRODUCTION))
        }

        test("semicolon split of two narrators") {
            ContributorParser.parse("Michael Kramer; Kate Reading", ContributorRole.NARRATOR) shouldBe
                listOf(row("Michael Kramer", ContributorRole.NARRATOR), row("Kate Reading", ContributorRole.NARRATOR))
        }

        test("Last, First single person is re-paired") {
            ContributorParser.parse("Sanderson, Brandon", ContributorRole.AUTHOR) shouldBe
                listOf(row("Brandon Sanderson", ContributorRole.AUTHOR))
        }

        test("comma-separated full names are two people") {
            ContributorParser.parse("Stephen King, Peter Straub", ContributorRole.AUTHOR) shouldBe
                listOf(row("Stephen King", ContributorRole.AUTHOR), row("Peter Straub", ContributorRole.AUTHOR))
        }

        test("ampersand split with Last, First on each side") {
            ContributorParser.parse("Friedman, Milton & Friedman, Rose", ContributorRole.AUTHOR) shouldBe
                listOf(row("Milton Friedman", ContributorRole.AUTHOR), row("Rose Friedman", ContributorRole.AUTHOR))
        }

        test("role suffix on a single person") {
            ContributorParser.parse("Emily Wilson - translator", ContributorRole.AUTHOR) shouldBe
                listOf(row("Emily Wilson", ContributorRole.TRANSLATOR))
        }

        test("' and ' splits two people") {
            ContributorParser.parse("Brandon Sanderson and Patrick Rothfuss", ContributorRole.AUTHOR) shouldBe
                listOf(row("Brandon Sanderson", ContributorRole.AUTHOR), row("Patrick Rothfuss", ContributorRole.AUTHOR))
        }

        test("Last, First plus a role suffix") {
            ContributorParser.parse("Wilson, Emily - translator", ContributorRole.AUTHOR) shouldBe
                listOf(row("Emily Wilson", ContributorRole.TRANSLATOR))
        }

        test("role aliases map") {
            ContributorParser.parse("A - read by", ContributorRole.AUTHOR) shouldBe listOf(row("A", ContributorRole.NARRATOR))
            ContributorParser.parse("B - introduction by", ContributorRole.AUTHOR) shouldBe listOf(row("B", ContributorRole.INTRODUCTION))
            ContributorParser.parse("C - intro", ContributorRole.AUTHOR) shouldBe listOf(row("C", ContributorRole.INTRODUCTION))
            ContributorParser.parse("D - illustrated by", ContributorRole.AUTHOR) shouldBe listOf(row("D", ContributorRole.ILLUSTRATOR))
        }

        test("unmapped role suffix keeps the person at the default role") {
            ContributorParser.parse("Stephen King - special guest", ContributorRole.AUTHOR) shouldBe
                listOf(row("Stephen King", ContributorRole.AUTHOR))
        }

        test("CJK name passes through unsplit") {
            ContributorParser.parse("东野圭吾", ContributorRole.AUTHOR) shouldBe listOf(row("东野圭吾", ContributorRole.AUTHOR))
        }

        test("duplicate (name, role) pairs are de-duplicated") {
            ContributorParser.parse("Stephen King; Stephen King", ContributorRole.AUTHOR) shouldBe
                listOf(row("Stephen King", ContributorRole.AUTHOR))
        }

        test("mixed separators all split (& and ; in one string)") {
            ContributorParser.parse("Brandon Sanderson & Patrick Rothfuss; Mary Robinette Kowal", ContributorRole.AUTHOR) shouldBe
                listOf(
                    row("Brandon Sanderson", ContributorRole.AUTHOR),
                    row("Patrick Rothfuss", ContributorRole.AUTHOR),
                    row("Mary Robinette Kowal", ContributorRole.AUTHOR),
                )
        }

        test("CJK name with a comma passes through unsplit") {
            ContributorParser.parse("东野, 圭吾", ContributorRole.AUTHOR) shouldBe listOf(row("东野, 圭吾", ContributorRole.AUTHOR))
        }

        test("odd-count bare-surname comma list drops the trailing unpaired chunk") {
            ContributorParser.parse("King, Stephen, Straub", ContributorRole.AUTHOR) shouldBe
                listOf(row("Stephen King", ContributorRole.AUTHOR))
        }

        test("blank input yields no contributors") {
            ContributorParser.parse("", ContributorRole.AUTHOR).shouldBeEmpty()
            ContributorParser.parse("   ", ContributorRole.AUTHOR).shouldBeEmpty()
        }

        test("personNames splits on explicit separators without role logic") {
            ContributorParser.personNames("Sanderson, Brandon; King, Stephen") shouldBe
                listOf("Sanderson, Brandon", "King, Stephen")
            ContributorParser.personNames("Solo Author") shouldBe listOf("Solo Author")
            ContributorParser.personNames("  ") shouldBe emptyList()
            ContributorParser.personNames("Tolkien, J.R.R. & Le Guin, Ursula K.") shouldBe
                listOf("Tolkien, J.R.R.", "Le Guin, Ursula K.")
            ContributorParser.personNames("Tolkien, J.R.R. and Le Guin, Ursula K.") shouldBe
                listOf("Tolkien, J.R.R.", "Le Guin, Ursula K.")
        }
    })
