package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.licenses_text_unavailable
import listenup.composeapp.generated.resources.licenses_view_license
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseDetailScreen(
    uniqueId: String,
    onNavigateBack: () -> Unit,
) {
    val rows by rememberLicenseRows()
    val row = rows.firstOrNull { it.uniqueId == uniqueId }

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(row?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LicenseDetailBody(row = row, modifier = Modifier.padding(padding))
    }
}

@Composable
private fun LicenseDetailBody(
    row: LicenseRow?,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    when {
        row == null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.licenses_text_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        !row.licenseText.isNullOrBlank() || row.url != null -> {
            // Show inline text (when available) and the canonical-source URL link below it.
            // The URL is kept visible even when full text is present — it is the authoritative
            // source and the user asked for it to remain as a supplement/fallback.
            Column(
                modifier =
                    modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            ) {
                if (!row.licenseText.isNullOrBlank()) {
                    Text(
                        text = row.licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }

                if (row.url != null) {
                    if (row.spdxId.isNotBlank() && row.licenseText.isNullOrBlank()) {
                        // Only show the SPDX label when there is no inline text to identify it.
                        Text(
                            text = row.spdxId,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(row.url) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.licenses_view_license),
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }
        }

        else -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.licenses_text_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
