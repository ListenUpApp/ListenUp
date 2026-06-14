package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared detail-screen hero scaffold used by the Book, Series, and Contributor detail screens.
 *
 * Owns the common shell: a cover-color [gradientColors] backdrop, a parallaxing [backdropMedia]
 * slot (cover or avatar — the caller owns its shape), and an emphasized display [title] that, on
 * compact widths, collapses into a pinned title beside the [navigation] row as the screen scrolls.
 *
 * The hero does NOT own the scroll container. The host screen keeps its `LazyColumn` and supplies
 * [collapseFraction] (0 = fully expanded, 1 = fully collapsed) derived from its list state, plus
 * [collapsing] = true on compact (animate) or false on wide (render the expanded form statically).
 *
 * @param collapseFraction lambda returning 0f..1f; a lambda (not a value) so reads stay skippable
 * @param collapsing whether to apply collapse/parallax (compact) or render static (wide)
 * @param gradientColors vertical-gradient stops the caller builds from its cover/palette colors
 * @param navigation back button + actions row; always visible. Receives a [pinnedTitle] slot that
 *   the row should place (e.g. centered) — it fades in as the hero collapses (empty when static)
 * @param title hero title — emphasized display when expanded, pinned title style when collapsed
 * @param backdropMedia the cover card or avatar; parallaxes on scroll
 * @param modifier optional modifier
 * @param subtitle optional secondary line under the title
 * @param belowTitle optional content under the title (stats, progress, bio)
 */
@Composable
fun DetailHero(
    collapseFraction: () -> Float,
    collapsing: Boolean,
    gradientColors: List<Color>,
    navigation: @Composable (pinnedTitle: @Composable () -> Unit) -> Unit,
    title: String,
    backdropMedia: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    belowTitle: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(gradientColors)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            navigation {
                if (collapsing) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = collapseFraction().coerceIn(0f, 1f) },
                    )
                }
            }

            Box(
                modifier =
                    Modifier.graphicsLayer {
                        if (collapsing) {
                            val f = collapseFraction().coerceIn(0f, 1f)
                            translationY = -size.height * 0.5f * f
                            alpha = 1f - f
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                backdropMedia()
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.displayLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(horizontal = 32.dp)
                        .then(
                            if (collapsing) {
                                Modifier.graphicsLayer { alpha = (1f - collapseFraction()).coerceIn(0f, 1f) }
                            } else {
                                Modifier
                            },
                        ),
            )

            subtitle?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            belowTitle?.let {
                Spacer(modifier = Modifier.height(12.dp))
                it()
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
