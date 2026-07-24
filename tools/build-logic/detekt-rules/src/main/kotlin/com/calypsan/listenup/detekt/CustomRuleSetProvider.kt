package com.calypsan.listenup.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class CustomRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("listenup-custom")

    override fun instance(): RuleSet =
        RuleSet(ruleSetId, listOf(::NoKoinInjectViewModelRule, ::NoHardcodedUiStringRule))
}
