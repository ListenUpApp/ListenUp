package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.client.design.components.ListenUpButton
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_inbox_issue_dismiss
import listenup.composeapp.generated.resources.admin_inbox_issue_metadata
import listenup.composeapp.generated.resources.admin_inbox_issue_metadata_fix
import listenup.composeapp.generated.resources.admin_inbox_issue_no_audio
import listenup.composeapp.generated.resources.admin_inbox_issue_no_audio_fix
import listenup.composeapp.generated.resources.admin_inbox_issue_title
import listenup.composeapp.generated.resources.admin_inbox_issue_title_fix
import listenup.composeapp.generated.resources.admin_inbox_issue_unknown
import listenup.composeapp.generated.resources.admin_inbox_issue_unknown_fix
import listenup.composeapp.generated.resources.admin_inbox_issue_unreadable
import listenup.composeapp.generated.resources.admin_inbox_issue_unreadable_fix
import listenup.composeapp.generated.resources.admin_inbox_needs_attention
import listenup.composeapp.generated.resources.admin_inbox_needs_attention_subtitle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The "needs attention" half of the inbox — folders the scanner walked but could not import.
 *
 * These are not books awaiting a decision, so they do not share the selection/release machinery
 * the held books use. They are statements that something went wrong, each paired with the thing the
 * user would actually do about it. Dismiss is the only action: per the design call, a user who can
 * see *why* a folder failed fixes it on disk, and rename/move tools inside the app would be a
 * second, worse file manager.
 */
internal fun LazyGridScope.scanIssueItems(
    issues: List<ScanIssue>,
    onDismiss: (String) -> Unit,
) {
    if (issues.isEmpty()) return

    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                text = stringResource(Res.string.admin_inbox_needs_attention),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.admin_inbox_needs_attention_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    issues.forEach { issue ->
        item(key = "scan-issue-${issue.id}", span = { GridItemSpan(maxLineSpan) }) {
            ScanIssueCard(issue = issue, onDismiss = { onDismiss(issue.id) })
        }
    }
}

@Composable
private fun ScanIssueCard(
    issue: ScanIssue,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(issue.reason.headline()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            // The folder is the thing the user goes and looks at, so it reads loudest after the
            // headline — and it is library-relative, matching what they see on disk.
            Text(
                text = issue.rootRelPath,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(issue.reason.remedy()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What the scanner literally reported. Kept last and quiet: useful when the remedy
            // above isn't enough, noise when it is.
            issue.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ListenUpButton(
                onClick = onDismiss,
                text = stringResource(Res.string.admin_inbox_issue_dismiss),
                filled = false,
                fillMaxWidth = false,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What went wrong, in the user's terms rather than the scanner's. */
private fun ScanIssueReason.headline(): StringResource =
    when (this) {
        ScanIssueReason.NO_RECOGNIZED_AUDIO -> Res.string.admin_inbox_issue_no_audio
        ScanIssueReason.FILE_UNREADABLE -> Res.string.admin_inbox_issue_unreadable
        ScanIssueReason.METADATA_PARSE_FAILED -> Res.string.admin_inbox_issue_metadata
        ScanIssueReason.TITLE_INFERENCE_FAILED -> Res.string.admin_inbox_issue_title
        ScanIssueReason.UNKNOWN -> Res.string.admin_inbox_issue_unknown
    }

/**
 * What to do about it.
 *
 * Every reason has one, because a notice the user cannot act on is just an apology. This is the
 * whole justification for classifying failures at all — if two reasons would give the same advice,
 * they did not need to be two reasons.
 */
private fun ScanIssueReason.remedy(): StringResource =
    when (this) {
        ScanIssueReason.NO_RECOGNIZED_AUDIO -> Res.string.admin_inbox_issue_no_audio_fix
        ScanIssueReason.FILE_UNREADABLE -> Res.string.admin_inbox_issue_unreadable_fix
        ScanIssueReason.METADATA_PARSE_FAILED -> Res.string.admin_inbox_issue_metadata_fix
        ScanIssueReason.TITLE_INFERENCE_FAILED -> Res.string.admin_inbox_issue_title_fix
        ScanIssueReason.UNKNOWN -> Res.string.admin_inbox_issue_unknown_fix
    }
