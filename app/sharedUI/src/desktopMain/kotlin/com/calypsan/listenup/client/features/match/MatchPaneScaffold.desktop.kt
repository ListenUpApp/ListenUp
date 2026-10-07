package com.calypsan.listenup.client.features.match

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldPaneScope
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal actual fun MatchPaneScaffold(
    navigator: ThreePaneScaffoldNavigator<Nothing>,
    listPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    detailPane: @Composable ThreePaneScaffoldPaneScope.() -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    PlatformBackHandler(enabled = navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        listPane = listPane,
        detailPane = detailPane,
        modifier = modifier,
    )
}
