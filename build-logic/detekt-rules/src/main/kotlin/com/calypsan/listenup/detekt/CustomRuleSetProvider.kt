package com.calypsan.listenup.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class CustomRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "listenup-custom"

    override fun instance(config: Config): RuleSet =
        RuleSet(ruleSetId, listOf(NoKoinInjectViewModelRule(config), NoHardcodedUiStringRule(config)))
}
