package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_part_of
import org.jetbrains.compose.resources.stringResource

/**
 * The series above this one — "Cosmere › Mistborn" — standing where the "SERIES" overline sits on a
 * top-level page. Every crumb is a real button at full touch size that opens that series (pushed,
 * so Back still returns here); the "›" separators are silent to TalkBack, and the row describes
 * itself as "Part of Cosmere, Mistborn". Wraps only between crumbs, never inside a name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesBreadcrumb(
    ancestors: List<SeriesCrumb>,
    onCrumbClick: (seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    centered: Boolean = true,
) {
    val haptics = LocalHaptics.current
    val description = "${stringResource(Res.string.series_part_of)} ${ancestors.joinToString(", ") { it.name }}"
    FlowRow(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement =
            if (centered) Arrangement.Center else Arrangement.Start,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ancestors.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            TextButton(
                onClick = {
                    haptics.press()
                    onCrumbClick(crumb.id)
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = color),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = crumb.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
