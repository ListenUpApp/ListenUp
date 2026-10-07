package com.calypsan.listenup.client.features.match

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The results | review list-detail scaffold, driven by [navigator]. On Android it is material3-adaptive's
 * `NavigableListDetailPaneScaffold`, whose predictive back slides Review away on one pane; that composable is
 * Android-only in the multiplatform artifact, so Desktop composes the same `ListDetailPaneScaffold` with a
 * plain back handler.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal expect fun MatchPaneScaffold(
    navigator: ThreePaneScaffoldNavigator<Nothing>,
    listPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    detailPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    modifier: Modifier,
)
