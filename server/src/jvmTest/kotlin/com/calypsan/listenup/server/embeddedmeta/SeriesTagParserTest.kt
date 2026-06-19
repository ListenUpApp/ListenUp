package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeriesTagParserTest :
    FunSpec({
        context("zipSeries") {
            test("null or blank name yields empty") {
                SeriesTagParser.zipSeries(null, "1") shouldBe emptyList()
                SeriesTagParser.zipSeries("  ", "1") shouldBe emptyList()
            }
            test("single series, no semicolons") {
                SeriesTagParser.zipSeries("Cosmere", "3") shouldBe listOf(SeriesEntry("Cosmere", "3"))
                SeriesTagParser.zipSeries("Cosmere", null) shouldBe listOf(SeriesEntry("Cosmere", null))
            }
            test("semicolon name + part with equal counts zips") {
                SeriesTagParser.zipSeries("Cosmere;Stormlight", "3;4") shouldBe
                    listOf(SeriesEntry("Cosmere", "3"), SeriesEntry("Stormlight", "4"))
            }
            test("count mismatch falls back to a single series (the whole raw name)") {
                SeriesTagParser.zipSeries("Cosmere;Stormlight", "3") shouldBe
                    listOf(SeriesEntry("Cosmere;Stormlight", "3"))
            }
            test("semicolon name but no semicolon in part is a single series") {
                SeriesTagParser.zipSeries("A;B", null) shouldBe listOf(SeriesEntry("A;B", null))
            }
            test("trims and drops blank chunks") {
                SeriesTagParser.zipSeries("A; B ;", "1; 2 ;") shouldBe
                    listOf(SeriesEntry("A", "1"), SeriesEntry("B", "2"))
            }
        }
        context("parsePacked") {
            test("packed name #seq pairs split on semicolons") {
                SeriesTagParser.parsePacked("Mistborn #1; Stormlight #4") shouldBe
                    listOf(SeriesEntry("Mistborn", "1"), SeriesEntry("Stormlight", "4"))
            }
            test("no sequence marker yields name with null sequence") {
                SeriesTagParser.parsePacked("Solo Series") shouldBe listOf(SeriesEntry("Solo Series", null))
            }
            test("non-numeric sequence preserved") {
                SeriesTagParser.parsePacked("Wheel #1a") shouldBe listOf(SeriesEntry("Wheel", "1a"))
            }
            test("blank input yields empty") {
                SeriesTagParser.parsePacked("   ") shouldBe emptyList()
            }
        }
    })
