package com.calypsan.listenup.client.data.repository.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [QueryUtils.toFtsQuery]'s per-token quoting and escaping rules.
 *
 * [SearchTokenizerCharacterizationTest] (`:app:sharedLogic` jvmTest) proves these queries
 * actually match punctuated names against real SQLite FTS5; these tests pin the exact string
 * shape [QueryUtils.toFtsQuery] produces, independent of a database.
 */
class QueryUtilsTest :
    FunSpec({

        test("a single plain token is wrapped in quotes with a trailing prefix star") {
            QueryUtils.toFtsQuery("brandon") shouldBe "\"brandon\"*"
        }

        test("multiple tokens each get their own quoted prefix term, ANDed by a space") {
            QueryUtils.toFtsQuery("brandon sanderson") shouldBe "\"brandon\"* \"sanderson\"*"
        }

        test("punctuation inside a token is preserved literally, not stripped") {
            QueryUtils.toFtsQuery("R.R.") shouldBe "\"R.R.\"*"
            QueryUtils.toFtsQuery("O'Brien") shouldBe "\"O'Brien\"*"
            QueryUtils.toFtsQuery("Anne-Marie") shouldBe "\"Anne-Marie\"*"
        }

        test("an embedded double quote is doubled per FTS5's escaping rule") {
            QueryUtils.toFtsQuery("6\" record") shouldBe "\"6\"\"\"* \"record\"*"
        }

        test("a lone asterisk typed by the user lands inside the quotes as literal text") {
            QueryUtils.toFtsQuery("*") shouldBe "\"*\"*"
        }

        test("multiple interior spaces collapse to one token boundary, matching prior behaviour") {
            QueryUtils.toFtsQuery("brandon   sanderson") shouldBe "\"brandon\"* \"sanderson\"*"
        }

        test("blank input produces an empty query") {
            QueryUtils.toFtsQuery("") shouldBe ""
            QueryUtils.toFtsQuery("   ") shouldBe ""
        }
    })
