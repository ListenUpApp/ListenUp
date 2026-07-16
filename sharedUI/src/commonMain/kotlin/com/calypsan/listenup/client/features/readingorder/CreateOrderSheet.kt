package com.calypsan.listenup.client.features.readingorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextArea
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_create
import listenup.composeapp.generated.resources.reading_orders_create_attribution
import listenup.composeapp.generated.resources.reading_orders_create_attribution_hint
import listenup.composeapp.generated.resources.reading_orders_create_name
import listenup.composeapp.generated.resources.reading_orders_create_private_sub
import listenup.composeapp.generated.resources.reading_orders_create_set_active
import listenup.composeapp.generated.resources.reading_orders_create_set_active_sub
import listenup.composeapp.generated.resources.reading_orders_create_title
import listenup.composeapp.generated.resources.reading_orders_privacy_private

/**
 * Modal sheet for creating a new reading order: name, optional free-text attribution, and two
 * toggles — private-by-default visibility, and whether to immediately follow the new order as the
 * series' active (spoiler-clock) order. The host's ViewModel owns the actual create call; this
 * sheet only collects the form fields.
 *
 * @param onCreate Called with the trimmed name, trimmed-or-null attribution, privacy flag, and
 *   set-active flag when "Create" is tapped.
 * @param onDismiss Dismisses the sheet without creating anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOrderSheet(
    onCreate: (name: String, attribution: String?, isPrivate: Boolean, setActive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var attribution by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var setActive by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                text = stringResource(Res.string.reading_orders_create_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )

            ListenUpTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.reading_orders_create_name),
            )

            Column {
                ListenUpTextArea(
                    value = attribution,
                    onValueChange = { attribution = it },
                    label = stringResource(Res.string.reading_orders_create_attribution),
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    text = stringResource(Res.string.reading_orders_create_attribution_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }

            CreateOrderToggleRow(
                icon = Icons.Filled.Lock,
                title = stringResource(Res.string.reading_orders_privacy_private),
                subtitle = stringResource(Res.string.reading_orders_create_private_sub),
                checked = isPrivate,
                onCheckedChange = { isPrivate = it },
            )
            CreateOrderToggleRow(
                icon = Icons.Filled.Bolt,
                title = stringResource(Res.string.reading_orders_create_set_active),
                subtitle = stringResource(Res.string.reading_orders_create_set_active_sub),
                checked = setActive,
                onCheckedChange = { setActive = it },
            )

            Button(
                onClick = {
                    onCreate(name.trim(), attribution.trim().ifBlank { null }, isPrivate, setActive)
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            ) {
                Text(stringResource(Res.string.common_create))
            }
        }
    }
}

/** One labeled toggle row (icon, title, subtitle, trailing [Switch]) inside [CreateOrderSheet]. */
@Composable
private fun CreateOrderToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
