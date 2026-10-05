package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_path_a11y
import listenup.composeapp.generated.resources.series_path_expand_a11y
import org.jetbrains.compose.resources.stringResource

/** At this many ancestors a path folds its middle into "…". */
private const val FOLD_AT_ANCESTORS = 3

/**
 * Where the book sits, one line per series: "Cosmere › Mistborn › Mistborn Era 1 #1". Every name is
 * its own link to that series. A line wraps only between names, never inside or truncating one; at
 * four or more levels its middle folds into a "…" button that unfolds in place.
 */
@Composable
internal fun SeriesPathLines(
    paths: List<BookSeriesPath>,
    onSeriesClick: (seriesId: String) -> Unit,
    contentColor: Color,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.series_path_a11y)
    Column(
        modifier = modifier.semantics { contentDescription = label },
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        paths.forEach { path ->
            SeriesPathLine(path = path, onSeriesClick = onSeriesClick, contentColor = contentColor, centered = centered)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesPathLine(
    path: BookSeriesPath,
    onSeriesClick: (String) -> Unit,
    contentColor: Color,
    centered: Boolean,
) {
    var expanded by remember(path.seriesId) { mutableStateOf(false) }
    val folds = path.ancestors.size >= FOLD_AT_ANCESTORS && !expanded
    val own = SeriesCrumb(path.seriesId, path.sequence?.let { "${path.seriesName} #$it" } ?: path.seriesName)
    val visible: List<SeriesCrumb?> =
        if (folds) {
            listOf(path.ancestors.first(), null, path.ancestors.last(), own)
        } else {
            path.ancestors + own
        }

    FlowRow(
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(17.dp),
        )
        visible.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            if (crumb == null) {
                val expandLabel = stringResource(Res.string.series_path_expand_a11y)
                PathButton(
                    text = "…",
                    contentColor = contentColor,
                    bold = false,
                    onClick = { expanded = true },
                    modifier = Modifier.semantics { contentDescription = expandLabel },
                )
            } else {
                PathButton(
                    text = crumb.name,
                    contentColor = contentColor,
                    bold = crumb === own,
                    onClick = { onSeriesClick(crumb.id) },
                )
            }
        }
    }
}

@Composable
private fun PathButton(
    text: String,
    contentColor: Color,
    bold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    TextButton(
        onClick = {
            haptics.press()
            onClick()
        },
        contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
