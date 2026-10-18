package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.home.HomeUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.home_couldnt_load
import listenup.composeapp.generated.resources.home_offline_pill
import org.jetbrains.compose.resources.stringResource

private val PillHeight = 32.dp
private val PillIcon = 18.dp

/**
 * The quiet offline pill under the greeting (spec §3.1): the device or the server is out of reach, and downloaded books
 * still play. Informational only — the dimmed cards below are what it explains. Announced politely (§8.2).
 */
@Composable
internal fun OfflinePill(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = PillHeight).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PillIcon),
            )
            Text(
                text = stringResource(Res.string.home_offline_pill),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Home could not load at all (spec §3.3 page error): the greeting stays, and the slot's place carries "We couldn't
 * load Home." with **Try again** ([onRetry] is `refresh()`). The pull-to-refresh lives in the Ready content, so this
 * screen carries its own way back.
 */
@Composable
internal fun HomeErrorBody(
    state: HomeUiState.Error,
    onRetry: () -> Unit,
    appHeader: AppHeaderSlot,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        // The error carries no name, so the greeting stands alone.
        appHeader { HomeHeader(timeOfDay = state.timeOfDay, firstName = null, isWide = false) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(Res.string.home_couldnt_load),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                FilledTonalButton(onClick = onRetry, modifier = Modifier.heightIn(min = RetryHeight)) {
                    Text(stringResource(Res.string.common_try_again))
                }
            }
        }
    }
}

private val RetryHeight = 48.dp
