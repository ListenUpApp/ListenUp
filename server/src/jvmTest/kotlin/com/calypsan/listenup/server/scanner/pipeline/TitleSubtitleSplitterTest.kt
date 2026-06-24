package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TitleSubtitleSplitterTest :
    FunSpec({
        test("splits on the first colon-space") {
            TitleSubtitleSplitter.split("Sapiens: A Brief History of Humankind") shouldBe
                ("Sapiens" to "A Brief History of Humankind")
            TitleSubtitleSplitter.split("Educated: A Memoir") shouldBe ("Educated" to "A Memoir")
        }
        test("non-greedy first colon — later colons stay in the subtitle") {
            TitleSubtitleSplitter.split("A: B: C") shouldBe ("A" to "B: C")
        }
        test("descriptive tail: a multi-colon title splits at the last ': A/An/The …' segment") {
            TitleSubtitleSplitter.split("The Land: Alliances: A LitRPG Saga") shouldBe
                ("The Land: Alliances" to "A LitRPG Saga")
        }
        test("descriptive tail: a single-colon descriptive subtitle is unchanged") {
            TitleSubtitleSplitter.split("Sapiens: A Brief History of Humankind") shouldBe
                ("Sapiens" to "A Brief History of Humankind")
        }
        test("descriptive tail: a non-descriptive final segment falls back to the first-colon split") {
            TitleSubtitleSplitter.split("It: Chapter Two") shouldBe ("It" to "Chapter Two")
        }
        test("no colon-space returns the title whole") {
            TitleSubtitleSplitter.split("Mistborn") shouldBe ("Mistborn" to null)
        }
        test("G1: bare colon without a space does not split") {
            TitleSubtitleSplitter.split("Ratio 3:1") shouldBe ("Ratio 3:1" to null)
            TitleSubtitleSplitter.split("Vol:2") shouldBe ("Vol:2" to null)
        }
        test("G2: an empty side does not split") {
            TitleSubtitleSplitter.split("Foo: ") shouldBe ("Foo:" to null)
        }
        test("G3: a volume/number subtitle does not split") {
            TitleSubtitleSplitter.split("Dune: 2") shouldBe ("Dune: 2" to null)
            TitleSubtitleSplitter.split("Foundation: Book 3") shouldBe ("Foundation: Book 3" to null)
            TitleSubtitleSplitter.split("Wheel of Time: Volume 14") shouldBe ("Wheel of Time: Volume 14" to null)
        }
        test("a non-numeric subtitle that starts with a short word still splits") {
            TitleSubtitleSplitter.split("It: Chapter Two") shouldBe ("It" to "Chapter Two")
        }
        test("trims both sides") {
            TitleSubtitleSplitter.split("Title :  Sub ") shouldBe ("Title" to "Sub")
        }
    })
