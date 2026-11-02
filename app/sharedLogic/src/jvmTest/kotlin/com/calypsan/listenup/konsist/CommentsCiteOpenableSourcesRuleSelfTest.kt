package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CommentsCiteOpenableSourcesRuleSelfTest :
    FunSpec({
        test("a plan-code citation counts") {
            citationLineCount("// reads this catch-up (spec §3.2 H5).") shouldBe 1
        }

        test("a public standard's section reference does not count") {
            citationLineCount("// Stored blocks, RFC 1951 §3.2.7.") shouldBe 0
        }

        test("a plain comment does not count") {
            citationLineCount("// Retries 3 times") shouldBe 0
        }
    })
