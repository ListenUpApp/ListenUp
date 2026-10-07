package com.calypsan.listenup.client.features.match

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal actual fun MatchPaneScaffold(
    navigator: ThreePaneScaffoldNavigator<Nothing>,
    listPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    detailPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    modifier: Modifier,
) {
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = listPane,
        detailPane = detailPane,
        modifier = modifier,
    )
}
