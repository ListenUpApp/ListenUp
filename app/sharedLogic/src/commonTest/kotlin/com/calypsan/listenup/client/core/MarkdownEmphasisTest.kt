package com.calypsan.listenup.client.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MarkdownEmphasisTest :
    FunSpec({
        test("a space before the closing stars moves outside them, so the run still reads as bold") {
            tightenMarkdownEmphasis("**From a new voice comes The Raven Scholar, the truth. **\n\nLet us fly.") shouldBe
                "**From a new voice comes The Raven Scholar, the truth.** \n\nLet us fly."
        }

        test("a space after the opening stars moves outside them too") {
            tightenMarkdownEmphasis("Read ** this** now") shouldBe "Read  **this** now"
        }

        test("underscore strong runs are tightened the same way") {
            tightenMarkdownEmphasis("__Bold __ text") shouldBe "__Bold__  text"
        }

        test("well-formed emphasis, nested italics and several runs on a line are left as written") {
            val text = "**A _b_ c** and **d** with *e* and __init__"
            tightenMarkdownEmphasis(text) shouldBe text
        }

        test("each run on a line is tightened on its own") {
            tightenMarkdownEmphasis("**One **then **two **") shouldBe "**One** then **two** "
        }

        test("a run never reaches across a line break") {
            val text = "**open\nclose**"
            tightenMarkdownEmphasis(text) shouldBe text
        }

        test("stars with nothing but space between them are not a run") {
            val text = "a **   ** b"
            tightenMarkdownEmphasis(text) shouldBe text
        }
    })
