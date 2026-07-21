package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_offline_body
import listenup.composeapp.generated.resources.book_detail_offline_title
import listenup.composeapp.generated.resources.book_detail_retry
import org.jetbrains.compose.resources.stringResource

/**
 * Banner displayed when the server looks unreachable.
 *
 * A point-of-need hint: it informs that streaming may fail while reassuring the
 * user that downloaded books still play — actions stay enabled (attempt-first).
 * The Retry action delegates connection recovery to the caller — this component
 * has no networking concern of its own.
 *
 * @param onRetryClick Called when the user taps the Retry button.
 * @param modifier Modifier applied to the outer surface.
 * @param compact When true, uses tighter padding and smaller icon sizes — suitable for
 *                narrow mobile layouts. Defaults to false (desktop / wide mobile).
 */
@Composable
fun OfflineBanner(
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bannerShape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
    val horizontalPadding = if (compact) 14.dp else 22.dp
    val verticalPadding = if (compact) 12.dp else 16.dp
    val iconContainerSize = if (compact) 40.dp else 50.dp
    val iconSize = if (compact) 22.dp else 27.dp
    val retryButtonHeight = if (compact) 40.dp else 46.dp
    val retryHorizontalPadding = if (compact) 16.dp else 20.dp

    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = bannerShape,
        color = errorContainer,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tinted circle icon
            Box(
                modifier =
                    Modifier
                        .size(iconContainerSize)
                        .background(
                            color = onErrorContainer.copy(alpha = 0.13f),
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = onErrorContainer,
                )
            }

            // Title + body
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.book_detail_offline_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onErrorContainer,
                )
                Text(
                    text = stringResource(Res.string.book_detail_offline_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onErrorContainer.copy(alpha = 0.85f),
                )
            }

            // Retry button: onErrorContainer container, errorContainer content
            Button(
                onClick = onRetryClick,
                modifier = Modifier.height(retryButtonHeight),
                shape = RoundedCornerShape(50),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        horizontal = retryHorizontalPadding,
                        vertical = 0.dp,
                    ),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = onErrorContainer,
                        contentColor = errorContainer,
                    ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.book_detail_retry),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
