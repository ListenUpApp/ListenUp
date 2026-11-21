package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.domain.model.DownloadPhase
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_download_badge_downloaded_a11y
import listenup.composeapp.generated.resources.library_download_badge_downloading_a11y
import listenup.composeapp.generated.resources.library_download_badge_failed_a11y
import listenup.composeapp.generated.resources.library_download_badge_paused_a11y
import listenup.composeapp.generated.resources.library_download_badge_queued_a11y
import listenup.composeapp.generated.resources.library_download_badge_resumes_automatically_a11y
import listenup.composeapp.generated.resources.library_download_badge_waiting_for_wifi_a11y
import org.jetbrains.compose.resources.stringResource

/** The badge's size on the cover (canvas: 26 dp). */
private val BadgeSize = 26.dp

/**
 * Where a book's download stands, as a 26 dp badge for the bottom-end corner of its cover (canvas `A-Library`): a
 * progress ring while it downloads, a clock while queued, Wi-Fi off while Wi-Fi only holds it, a resume clock while the
 * system holds it, pause bars when the person paused it, an alert on the error container when it failed, and a
 * download mark once it is on the device. White, so it reads on any art. Only a book with a download has one.
 *
 * Decorative: the state belongs in the cover's accessible name ([downloadBadgeDescription]), not a stop of its own.
 */
@Composable
fun DownloadBadge(
    phase: DownloadPhase,
    modifier: Modifier = Modifier,
) {
    if (phase == DownloadPhase.NotDownloaded) return
    val failed = phase is DownloadPhase.Failed
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else Color.White
    val content = if (failed) MaterialTheme.colorScheme.onErrorContainer else BadgeInk
    Box(
        modifier =
            modifier
                .size(BadgeSize)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (phase is DownloadPhase.Downloading) {
            CircularProgressIndicator(
                progress = { phase.percent / PERCENT },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.4.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            badgeIcon(phase)?.let { icon ->
                Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun badgeIcon(phase: DownloadPhase): ImageVector? =
    when (phase) {
        DownloadPhase.Queued -> ListenUpIcons.Schedule
        DownloadPhase.WaitingForWifi -> ListenUpIcons.WifiOff
        DownloadPhase.WaitingToResume -> ListenUpIcons.History
        is DownloadPhase.PausedByYou -> ListenUpIcons.PauseFilled
        is DownloadPhase.Failed -> ListenUpIcons.ErrorFilled
        DownloadPhase.Downloaded -> ListenUpIcons.DownloadFilled
        is DownloadPhase.Downloading, DownloadPhase.NotDownloaded -> null
    }

/**
 * What the badge says, for the cover's accessible name ("Project Hail Mary, downloading, 33%"): null when the book has
 * no download.
 */
@Composable
fun downloadBadgeDescription(phase: DownloadPhase?): String? =
    when (phase) {
        null, DownloadPhase.NotDownloaded -> {
            null
        }

        is DownloadPhase.Downloading -> {
            stringResource(
                Res.string.library_download_badge_downloading_a11y,
                "${phase.percent}%",
            )
        }

        DownloadPhase.Queued -> {
            stringResource(Res.string.library_download_badge_queued_a11y)
        }

        DownloadPhase.WaitingForWifi -> {
            stringResource(Res.string.library_download_badge_waiting_for_wifi_a11y)
        }

        DownloadPhase.WaitingToResume -> {
            stringResource(Res.string.library_download_badge_resumes_automatically_a11y)
        }

        is DownloadPhase.PausedByYou -> {
            stringResource(Res.string.library_download_badge_paused_a11y)
        }

        is DownloadPhase.Failed -> {
            stringResource(Res.string.library_download_badge_failed_a11y)
        }

        DownloadPhase.Downloaded -> {
            stringResource(Res.string.library_download_badge_downloaded_a11y)
        }
    }

/** The canvas's ink on the white badge: the theme's dark neutral, which stays dark on white in both themes. */
private val BadgeInk = Color(0xFF231917)

private const val PERCENT = 100f
