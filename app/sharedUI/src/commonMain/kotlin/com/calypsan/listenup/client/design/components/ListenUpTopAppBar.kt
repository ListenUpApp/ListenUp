package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back

/**
 * The canonical standard top app bar. A thin wrapper over Material 3 [TopAppBar] that
 * presets the app's ExtraBold title style and an optional back affordance.
 *
 * It self-insets the status bar (Material 3 [TopAppBar]'s default `windowInsets`) while its
 * container still draws to the screen top — the idiomatic edge-to-edge behavior. Feature
 * screens use this instead of hand-rolling a `Row`, so the "controls trapped under the status
 * bar" bug class (e.g. the old `EditProfileTopBar`) cannot recur. Immersive screens that bleed
 * a hero behind the status bar use [HeroNavRow] instead.
 *
 * @param title Bar title text.
 * @param modifier Modifier for the bar.
 * @param onBack If non-null, renders a leading back [IconButton] that invokes it.
 * @param actions Trailing action slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenUpTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
            }
        },
        actions = actions,
    )
}
