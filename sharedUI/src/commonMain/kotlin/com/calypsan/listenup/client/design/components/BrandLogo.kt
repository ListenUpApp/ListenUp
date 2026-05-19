package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.LocalDarkTheme
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.listenup_logo_black
import listenup.composeapp.generated.resources.listenup_logo_white
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.common_listenup_logo

/**
 * ListenUp brand logo that adapts to light/dark theme.
 *
 * Uses the shared Compose Resources for cross-platform compatibility.
 *
 * @param modifier Optional modifier
 * @param size Logo size (default 160.dp)
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
) {
    val isDarkTheme = LocalDarkTheme.current
    val logoRes =
        if (isDarkTheme) {
            Res.drawable.listenup_logo_white
        } else {
            Res.drawable.listenup_logo_black
        }

    Image(
        painter = painterResource(logoRes),
        contentDescription = stringResource(Res.string.common_listenup_logo),
        modifier = modifier.size(size),
    )
}
