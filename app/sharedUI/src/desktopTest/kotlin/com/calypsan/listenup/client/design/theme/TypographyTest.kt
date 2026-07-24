package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class TypographyTest :
    FunSpec({
        test("emphasized type roles are populated for the bold identity") {
            ListenUpTypography.titleLargeEmphasized shouldNotBe ListenUpTypography.titleLarge
            ListenUpTypography.headlineMediumEmphasized shouldNotBe ListenUpTypography.headlineMedium
            ListenUpTypography.displayLargeEmphasized shouldNotBe null
        }
    })
