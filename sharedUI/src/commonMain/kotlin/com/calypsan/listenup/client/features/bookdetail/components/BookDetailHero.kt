package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing

/**
 * Centered "compact" hero for the Book Detail screen (phone layout).
 *
 * Stacks a 200×200 cover card, an optional overline in the brand coral, a large display title
 * (max 2 lines), a subtitle, an author line, and an optional narrator line — all centered on the
 * [MaterialTheme.colorScheme.surface] background. Stat chips belong to the StatsRow above the
 * scroll content, not here.
 *
 * @param coverPath Local cover file path, or null to show the placeholder
 * @param bookId Book ID for server-URL fallback cover loading
 * @param title Book title (max 2 lines, ellipsised)
 * @param overline Short descriptor shown above the title in the primary brand colour (e.g. genre
 *   and classification); null hides the row
 * @param subtitle Series + sequence string (e.g. "A Song of Ice and Fire · Book One"); null hides
 * @param authorLine Primary author line (e.g. "George R.R. Martin")
 * @param narratorLine Narrator credit string; null hides the row
 * @param progress Playback progress from 0.0 to 1.0; null hides the [ProgressOverlay]
 * @param timeRemaining Formatted time remaining (e.g. "21h 30m left"); null hides the label
 * @param modifier Optional layout modifier
 */
@Composable
fun CompactHero(
    coverPath: String?,
    bookId: String,
    title: String,
    overline: String?,
    subtitle: String?,
    authorLine: String,
    narratorLine: String?,
    progress: Float?,
    timeRemaining: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Cover — 200×200 dp, with an optional progress pill at the bottom
        ElevatedCoverCard(
            path = coverPath,
            bookId = bookId,
            contentDescription = title,
            modifier = Modifier.size(200.dp),
        ) {
            progress?.let { prog ->
                ProgressOverlay(
                    progress = prog,
                    timeRemaining = timeRemaining,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Overline — genre / classification in brand coral; hidden when null
        if (overline != null) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                // larger cover→overline gap than the 8dp column rhythm, per the design
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        // Title — large display text, max 2 lines
        Text(
            text = title,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Subtitle — series · book number; hidden when null
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Author
        Text(
            text = authorLine,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        // Narrator — hidden when null
        if (narratorLine != null) {
            Text(
                text = narratorLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Wide "color band" hero for the Book Detail screen (tablet / desktop layout).
 *
 * A [MaterialTheme.colorScheme.primaryContainer] surface that spans the full width, carrying the
 * book identity — cover, overline, title, subtitle, author, and narrator — arranged in a horizontal
 * row. Identity only: no Play FAB (the Play + Download group lives in a separate actions section).
 *
 * A decorative oversized blob is rendered top-right behind the content at 16 % primary opacity,
 * clipped to the band so it never overflows. The shape is intentionally organic to break up the
 * rectangular band without distracting from the content.
 *
 * @param coverPath Local cover file path, or null to show the placeholder
 * @param bookId Book ID for server-URL fallback cover loading
 * @param title Book title (max 2 lines, ellipsised)
 * @param overline Short descriptor shown above the title (e.g. genre / classification); null hides
 * @param subtitle Series + sequence string (e.g. "A Song of Ice and Fire · Book One"); null hides
 * @param authorLine Primary author line (e.g. "George R.R. Martin")
 * @param narratorLine Narrator credit string; null hides the row
 * @param progress Playback progress from 0.0 to 1.0; null hides the [ProgressOverlay]
 * @param timeRemaining Formatted time remaining (e.g. "21h 30m left"); null hides the label
 * @param modifier Optional layout modifier
 */
@Composable
fun WideHeroBand(
    coverPath: String?,
    bookId: String,
    title: String,
    overline: String?,
    subtitle: String?,
    authorLine: String,
    narratorLine: String?,
    progress: Float?,
    timeRemaining: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Clip the blob to the band so it never overflows the surface bounds
        Box(modifier = Modifier.fillMaxWidth().clip(ContentShapes.card)) {
            // Decorative background blob — top-right, oversized, organic corners, very subtle
            Box(
                modifier =
                    Modifier
                        .size(340.dp)
                        .offset(x = (-80).dp, y = (-100).dp)
                        .align(Alignment.TopEnd)
                        .clip(
                            RoundedCornerShape(
                                topStart = 40.dp,
                                topEnd = 120.dp,
                                bottomEnd = 80.dp,
                                bottomStart = 160.dp,
                            ),
                        ),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.matchParentSize(),
                ) {}
            }

            // Main content row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 44.dp, vertical = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cover — 240×240 dp with optional progress pill
                ElevatedCoverCard(
                    path = coverPath,
                    bookId = bookId,
                    contentDescription = title,
                    modifier = Modifier.size(240.dp),
                ) {
                    progress?.let { prog ->
                        ProgressOverlay(
                            progress = prog,
                            timeRemaining = timeRemaining,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                // Identity column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Overline — genre / classification, slightly muted; hidden when null
                    if (overline != null) {
                        Text(
                            text = overline.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }

                    // Title — large display, max 2 lines
                    Text(
                        text = title,
                        style =
                            MaterialTheme.typography.displaySmall.copy(
                                fontFamily = DisplayFontFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    // Subtitle — series · book number; hidden when null
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                        )
                    }

                    // Author · narrator row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        Text(
                            text = authorLine,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        if (narratorLine != null) {
                            // Dot separator
                            Surface(
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.size(4.dp),
                            ) {}

                            Text(
                                text = narratorLine,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                            )
                        }
                    }
                }
            }
        }
    }
}
