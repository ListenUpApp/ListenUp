package com.calypsan.listenup.client.design.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe

class EmphasizedTypographyTest :
    FunSpec({
        test("displayLargeEmphasized is populated and at least as heavy as displayLarge") {
            val t = ListenUpTypography
            t.displayLargeEmphasized shouldNotBe t.displayLarge
            (t.displayLargeEmphasized.fontWeight?.weight ?: 0) shouldBeGreaterThanOrEqual
                (t.displayLarge.fontWeight?.weight ?: 0)
        }
        test("titleLargeEmphasized is populated and distinct from titleLarge") {
            val t = ListenUpTypography
            t.titleLargeEmphasized shouldNotBe t.titleLarge
        }
    })
