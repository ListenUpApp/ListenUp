package com.calypsan.listenup.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Forbids `koinInject<*ViewModel>()` and `viewModel: *ViewModel = koinInject()`.
 *
 * Per Finding 02 D1 + Finding 11 D2 + Koin canonical guidance, ViewModels resolved
 * inside Compose must use `koinViewModel()`. `koinInject<NonVMDep>()` for non-VM
 * dependencies (analytics, repositories, services) is the correct pattern and is not
 * flagged. Types whose name does not end exactly in `ViewModel` (e.g. `LoginViewModelFactory`)
 * are also not flagged.
 */
class NoKoinInjectViewModelRule(
    config: Config,
) : Rule(
        config,
        description = "ViewModels must not be resolved via koinInject(); use koinViewModel().",
    ) {
    override val ruleName = RuleName("NoKoinInjectViewModel")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee != "koinInject") return

        // Pattern 1: koinInject<XxxViewModel>()
        val typeArg =
            expression.typeArguments
                .firstOrNull()
                ?.typeReference
                ?.text ?: ""
        if (typeArg.endsWithViewModel()) {
            report(
                Finding(
                    Entity.from(expression),
                    "koinInject<$typeArg>() resolves a ViewModel via the Activity store. " +
                        "Use koinViewModel().",
                ),
            )
            return
        }

        // Pattern 2: viewModel: XxxViewModel = koinInject() — declared type ends in ViewModel
        val declaredType =
            when (val parent = expression.parent) {
                is KtProperty -> parent.typeReference?.text
                is KtParameter -> parent.typeReference?.text
                else -> null
            } ?: return

        if (declaredType.endsWithViewModel()) {
            report(
                Finding(
                    Entity.from(expression),
                    "$declaredType = koinInject() resolves a ViewModel via the Activity store. " +
                        "Use koinViewModel().",
                ),
            )
        }
    }

    /**
     * Returns true if the simple-name portion of the type reference ends exactly in
     * `ViewModel`. Strips generics and package qualifiers so `com.foo.LoginViewModel`,
     * `LoginViewModel<State>`, and bare `LoginViewModel` all match — but
     * `LoginViewModelFactory` does not.
     */
    private fun String.endsWithViewModel(): Boolean {
        val withoutGenerics = substringBefore('<').trim()
        val simpleName = withoutGenerics.substringAfterLast('.')
        return simpleName.endsWith("ViewModel")
    }
}
