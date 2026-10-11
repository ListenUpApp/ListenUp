package com.calypsan.listenup.domain.storyworld

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class MentionTokensTest :
    FunSpec({
        context("extractMentionIds") {
            test("returns an empty set when the text has no tokens") {
                MentionTokens.extractMentionIds("just plain text, nothing here").shouldBeEmpty()
            }

            test("returns every id, deduplicated") {
                val text = "[[e:char-1|Darrow]] met [[e:char-2|Mustang]] at [[e:loc-1|the Institute]], then [[e:char-1|Darrow]] left."
                MentionTokens.extractMentionIds(text) shouldBe setOf("char-1", "char-2", "loc-1")
            }
        }

        context("render") {
            test("prefers the live name and falls back to the cached one") {
                val text = "[[e:char-1|Darrow]] and [[e:char-2|Mustang]] spoke."
                MentionTokens.render(text) { id -> if (id == "char-1") "Reaper" else null } shouldBe "Reaper and Mustang spoke."
            }

            test("returns text with no tokens unchanged") {
                MentionTokens.render("no mentions here") { "never called" } shouldBe "no mentions here"
            }
        }

        context("malformed tokens are literal text") {
            test("unterminated, empty-id and pipeless tokens are neither extracted nor rendered") {
                listOf("oops [[e:char-1|Darrow never closed", "oops [[e:|Darrow]] empty id", "oops [[e:char-1]] no pipe")
                    .forEach { text ->
                        MentionTokens.extractMentionIds(text).shouldBeEmpty()
                        MentionTokens.render(text) { "Reaper" } shouldBe text
                    }
            }
        }

        context("token round-trips and escaping") {
            test("a written token extracts to its id and renders its cached name") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Darrow")
                MentionTokens.extractMentionIds(written) shouldBe setOf("char-1")
                MentionTokens.render(written) { null } shouldBe "Darrow"
            }

            test("a pipe becomes a broken bar and a double bracket gains a space; a lone bracket is kept") {
                MentionTokens.render(MentionTokens.token("c", "Darrow|Reaper")) { null } shouldBe "Darrow¦Reaper"
                MentionTokens.render(MentionTokens.token("c", "Darrow]]Reaper")) { null } shouldBe "Darrow] ]Reaper"
                MentionTokens.render(MentionTokens.token("c", "Darrow [Helldiver]")) { null } shouldBe "Darrow [Helldiver]"
            }
        }
    })
