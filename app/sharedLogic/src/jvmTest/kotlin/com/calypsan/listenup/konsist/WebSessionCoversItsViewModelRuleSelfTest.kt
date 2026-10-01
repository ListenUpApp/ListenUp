package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/** Planted factory files for [actionCoverage], so the rule is known to credit the right receiver. */
class WebSessionCoversItsViewModelRuleSelfTest :
    FunSpec({
        val actions =
            mapOf(
                "DiscoverViewModel" to listOf("refresh"),
                "ActivityFeedViewModel" to listOf("refresh", "loadMore"),
                "RestoreViewModel" to listOf("requestRestore", "confirmRestore", "cancelRestore"),
            )

        fun unwired(code: String) = actionCoverage(code, actions).filterNot { it.isWired }.map { it.qualifiedName }

        test("wiring one ViewModel's refresh does not credit another's in the same file") {
            val code =
                """
                |fun graphDiscover(koin: Koin): OpenDiscover =
                |    {
                |        val discover = koin.get<DiscoverViewModel>()
                |        val activity = koin.get<ActivityFeedViewModel>()
                |        DiscoverSession(
                |            activity = activity.state,
                |            onMore = activity::loadMore,
                |            onRefresh = discover::refresh,
                |        )
                |    }
                """.trimMargin()

            unwired(code) shouldContainExactlyInAnyOrder listOf("ActivityFeedViewModel.refresh")
        }

        test("a call and a function reference both count as wiring") {
            val code =
                """
                |fun graphDiscover(koin: Koin): OpenDiscover =
                |    {
                |        val discover = koin.get<DiscoverViewModel>()
                |        val activity = koin.get<ActivityFeedViewModel>()
                |        activity.refresh()
                |        DiscoverSession(
                |            onMore = { activity.loadMore(page = 2) },
                |            onRefresh = discover::refresh,
                |        )
                |    }
                """.trimMargin()

            unwired(code) shouldContainExactlyInAnyOrder emptyList()
        }

        test("a parametersOf resolution is tracked, and a real miss is still flagged") {
            val code =
                """
                |fun graphRestore(koin: Koin): OpenRestore =
                |    { backupId ->
                |        val viewModel = koin.get<RestoreViewModel> { parametersOf(backupId) }
                |        RestoreSession(
                |            onRequest = viewModel::requestRestore,
                |            onConfirm = viewModel::confirmRestore,
                |        )
                |    }
                """.trimMargin()

            unwired(code) shouldContainExactlyInAnyOrder listOf("RestoreViewModel.cancelRestore")
        }

        test("one binding name reused across factories is charged to the ViewModel each resolves") {
            val code =
                """
                |fun graphDiscover(koin: Koin): OpenDiscover =
                |    {
                |        val viewModel = koin.get<DiscoverViewModel>()
                |        DiscoverSession(onRefresh = viewModel::refresh)
                |    }
                |
                |fun graphActivity(koin: Koin): OpenActivity =
                |    {
                |        val viewModel = koin.get<ActivityFeedViewModel>()
                |        ActivitySession(onMore = viewModel::loadMore)
                |    }
                """.trimMargin()

            unwired(code) shouldContainExactlyInAnyOrder listOf("ActivityFeedViewModel.refresh")
        }

        test("a resolution that binds no name falls back to matching the action anywhere in the file") {
            val code =
                """
                |fun graphActivity(koin: Koin): OpenActivity =
                |    {
                |        ActivitySession(koin.get<ActivityFeedViewModel>().state, onMore = { loadMore() })
                |    }
                """.trimMargin()

            unwired(code) shouldContainExactlyInAnyOrder listOf("ActivityFeedViewModel.refresh")
        }
    })
