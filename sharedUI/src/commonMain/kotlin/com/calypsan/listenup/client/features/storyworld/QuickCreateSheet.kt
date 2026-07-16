package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.storyworld.components.icon
import com.calypsan.listenup.client.features.storyworld.components.singularLabel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_create
import listenup.composeapp.generated.resources.story_world_quick_create_title

/**
 * Small modal sheet for minting a brand-new entity from the composer's quick-create affordance:
 * an editable name (prefilled from the open mention trigger's query) and a kind picker, defaulting
 * to [EntityKind.CHARACTER]. The host calls [onCreate] with the (possibly-edited) name and chosen
 * kind — the composer ViewModel's `quickCreate` mints the entity and inserts it as a mention; this
 * sheet owns no ViewModel of its own.
 *
 * @param initialName The mention trigger's in-progress query, prefilled into the name field.
 * @param onCreate Called with the trimmed name and chosen kind when "Create" is tapped.
 * @param onDismiss Dismisses the sheet without creating anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCreateSheet(
    initialName: String,
    onCreate: (String, EntityKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf(EntityKind.CHARACTER) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { QuickCreateDragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.story_world_quick_create_title, name.ifBlank { initialName }),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )

            ListenUpTextField(value = name, onValueChange = { name = it })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntityKind.entries.forEach { candidate ->
                    FilterChip(
                        selected = kind == candidate,
                        onClick = { kind = candidate },
                        label = { Text(candidate.singularLabel()) },
                        leadingIcon = {
                            Icon(
                                imageVector = candidate.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }

            Button(
                onClick = { onCreate(name.trim(), kind) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            ) {
                Text(stringResource(Res.string.common_create))
            }
        }
    }
}

@Composable
private fun QuickCreateDragHandle() {
    Surface(
        modifier = Modifier.padding(vertical = 12.dp).width(32.dp).height(4.dp),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    ) {}
}
