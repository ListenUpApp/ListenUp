package com.calypsan.listenup.api.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class MentionTokensTest :
    FunSpec({

        context("extractMentionIds") {
            test("returns an empty set when the text has no tokens") {
                MentionTokens.extractMentionIds("just plain text, nothing here").shouldBeEmpty()
            }

            test("returns the single id for one token") {
                val text = "Before the fight, [[e:char-1|Kaladin]] took a breath."
                MentionTokens.extractMentionIds(text) shouldBe setOf("char-1")
            }

            test("returns every id for multiple distinct tokens") {
                val text = "[[e:char-1|Kaladin]] met [[e:char-2|Shallan]] at [[e:loc-1|Urithiru]]."
                MentionTokens.extractMentionIds(text) shouldBe setOf("char-1", "char-2", "loc-1")
            }

            test("deduplicates repeated mentions of the same entity") {
                val text = "[[e:char-1|Kaladin]] spoke to [[e:char-1|Kaladin]] again."
                MentionTokens.extractMentionIds(text) shouldBe setOf("char-1")
            }
        }

        context("render") {
            test("substitutes the live name from nameFor") {
                val text = "Then [[e:char-1|Kaladin]] arrived."
                val rendered = MentionTokens.render(text) { id -> if (id == "char-1") "Stormblessed" else null }
                rendered shouldBe "Then Stormblessed arrived."
            }

            test("falls back to the cached display name when nameFor returns null") {
                val text = "Then [[e:char-1|Kaladin]] arrived."
                val rendered = MentionTokens.render(text) { null }
                rendered shouldBe "Then Kaladin arrived."
            }

            test("mixes live names and cached-name fallbacks within one string") {
                val text = "[[e:char-1|Kaladin]] and [[e:char-2|Shallan]] spoke."
                val rendered = MentionTokens.render(text) { id -> if (id == "char-1") "Stormblessed" else null }
                rendered shouldBe "Stormblessed and Shallan spoke."
            }

            test("returns text with no tokens unchanged") {
                val text = "no mentions in here at all"
                MentionTokens.render(text) { "should never be called" } shouldBe text
            }
        }

        context("malformed tokens are left as literal text") {
            test("unterminated token (no closing ]]) is not extracted and passes through render unchanged") {
                val text = "oops [[e:char-1|Kaladin never closed"
                MentionTokens.extractMentionIds(text).shouldBeEmpty()
                MentionTokens.render(text) { "Stormblessed" } shouldBe text
            }

            test("empty id is not extracted and passes through render unchanged") {
                val text = "oops [[e:|Kaladin]] empty id"
                MentionTokens.extractMentionIds(text).shouldBeEmpty()
                MentionTokens.render(text) { "Stormblessed" } shouldBe text
            }

            test("missing pipe is not extracted and passes through render unchanged") {
                val text = "oops [[e:char-1]] no pipe"
                MentionTokens.extractMentionIds(text).shouldBeEmpty()
                MentionTokens.render(text) { "Stormblessed" } shouldBe text
            }
        }

        context("token round-trips") {
            test("token() then extractMentionIds() returns exactly the written id") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Kaladin")
                MentionTokens.extractMentionIds(written) shouldBe setOf("char-1")
            }

            test("rendering a freshly written token with a null lookup yields the cached name") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Kaladin")
                MentionTokens.render(written) { null } shouldBe "Kaladin"
            }
        }

        context("escaping") {
            test("a pipe in the display name is sanitized to the broken-bar substitute and round-trips") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Kaladin|Stormblessed")
                MentionTokens.extractMentionIds(written) shouldBe setOf("char-1")
                MentionTokens.render(written) { null } shouldBe "Kaladin¦Stormblessed"
            }

            test("a lone bracket in the display name needs no escaping and round-trips verbatim") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Kaladin [Bridge Four]")
                MentionTokens.extractMentionIds(written) shouldBe setOf("char-1")
                MentionTokens.render(written) { null } shouldBe "Kaladin [Bridge Four]"
            }

            test("a double bracket in the display name is sanitized with an inserted space and round-trips") {
                val written = MentionTokens.token(entityId = "char-1", displayName = "Kaladin]]Stormblessed")
                MentionTokens.extractMentionIds(written) shouldBe setOf("char-1")
                MentionTokens.render(written) { null } shouldBe "Kaladin] ]Stormblessed"
            }
        }
    })
