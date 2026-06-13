package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [toPlainTextPreview] — the flattener behind the Book Detail "About" collapsed teaser.
 * The expanded view renders full Markdown; the clamped teaser must show clean prose, so HTML tags
 * AND Markdown emphasis markers are removed (keeping their inner text) rather than leaking through
 * as literal `**` / `#` / `<i>` symbols.
 */
class DescriptionPreviewTest :
    FunSpec({

        test("strips bold and italic asterisks, keeping the inner text") {
            "**bold**".toPlainTextPreview() shouldBe "bold"
            "*italic*".toPlainTextPreview() shouldBe "italic"
        }

        test("strips underscore bold, strikethrough, and inline code") {
            "__bold__".toPlainTextPreview() shouldBe "bold"
            "~~gone~~".toPlainTextPreview() shouldBe "gone"
            "`code`".toPlainTextPreview() shouldBe "code"
        }

        test("strips heading, blockquote, and list markers") {
            "# Heading".toPlainTextPreview() shouldBe "Heading"
            "> quoted".toPlainTextPreview() shouldBe "quoted"
            "- item".toPlainTextPreview() shouldBe "item"
            "1. first".toPlainTextPreview() shouldBe "first"
        }

        test("reduces a link to its label") {
            "See [Audible](https://audible.com) now".toPlainTextPreview() shouldBe "See Audible now"
        }

        test("still strips HTML tags and collapses whitespace") {
            "<i>Italic</i>   <b>Bold</b>".toPlainTextPreview() shouldBe "Italic Bold"
        }

        test("removes the space a stripped tag leaves before punctuation") {
            "Ends in emphasis <i>here</i>.".toPlainTextPreview() shouldBe "Ends in emphasis here."
        }

        test("flattens a real-world mixed HTML + Markdown description") {
            val raw = "**The apocalypse will be televised!** A man. His ex-girlfriend's cat."
            raw.toPlainTextPreview() shouldBe "The apocalypse will be televised! A man. His ex-girlfriend's cat."
        }

        test("leaves clean prose untouched") {
            "Just a plain sentence.".toPlainTextPreview() shouldBe "Just a plain sentence."
        }
    })
