package com.calypsan.listenup.client.features.setup.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.etaMinutes
import com.calypsan.listenup.client.features.auth.components.BrandMark
import com.calypsan.listenup.client.features.setup.components.SetupHeroBlob
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_scan_continue
import listenup.composeapp.generated.resources.library_scan_progress_percent
import listenup.composeapp.generated.resources.library_scan_progress_percent_eta
import listenup.composeapp.generated.resources.library_scan_stalled_message
import listenup.composeapp.generated.resources.library_scan_stalled_settings_hint
import listenup.composeapp.generated.resources.library_setup_building_title
import listenup.composeapp.generated.resources.scan_building_subtitle
import listenup.composeapp.generated.resources.scan_files_total
import listenup.composeapp.generated.resources.scan_recently_matched
import org.jetbrains.compose.resources.stringResource

private const val ETA_TICK_MS = 1_000L

/**
 * Full-screen "Building your library" gate, shown while the initial population scan runs INSTEAD of
 * the app shell. Adaptive from the actual available width (via [BoxWithConstraints]): a vertically
 * centred phone shell below [TwoPaneMinWidth], a brand/content split panel at or above it — mirroring
 * [com.calypsan.listenup.client.features.setup.LibrarySetupScreen]. Drives live progress, ETA and
 * matched-book stats off [scanProgress]; renders the loader + headline alone when it's null (the
 * brief "finishing up" tail after the scan's Completed event clears granular progress).
 *
 * When [stalled] is true the scan has gone quiet long enough to be considered stuck, so a
 * never-stranded escape is shown: an [onContinue] action to enter the partial library now, plus a
 * hint that the scan can be re-run from Settings.
 */
@Composable
fun LibraryScanScreen(
    scanProgress: ScanProgressState?,
    modifier: Modifier = Modifier,
    stalled: Boolean = false,
    onContinue: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            if (maxWidth >= TwoPaneMinWidth) {
                DesktopLayout(progress = scanProgress, stalled = stalled, onContinue = onContinue)
            } else {
                PhoneLayout(progress = scanProgress, stalled = stalled, onContinue = onContinue)
            }
        }
    }
}

// ──────────────────────────── PHONE ────────────────────────────

@Composable
private fun PhoneLayout(
    progress: ScanProgressState?,
    stalled: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScanBody(progress = progress, wide = false, stalled = stalled, onContinue = onContinue)
    }
}

// ──────────────────────────── DESKTOP ────────────────────────────

@Composable
private fun DesktopLayout(
    progress: ScanProgressState?,
    stalled: Boolean,
    onContinue: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val brandWidth = (maxWidth * 0.42f).coerceIn(360.dp, 560.dp)
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(brandWidth)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                SetupHeroBlob(modifier = Modifier.offset(x = 280.dp, y = (-90).dp))
                Column(
                    modifier = Modifier.fillMaxSize().systemBarsPadding().padding(52.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    BrandMark(onColor = true)
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = stringResource(Res.string.scan_building_subtitle),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text =
                            "We're scanning your folders and matching every audiobook — " +
                                "your shelves fill in as we go.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            ScanBody(progress = progress, wide = true, stalled = stalled, onContinue = onContinue)
        }
    }
}

// ──────────────────────────── SHARED ────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ScanBody(
    progress: ScanProgressState?,
    wide: Boolean,
    stalled: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = if (wide) 500.dp else 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress == null) {
            // "Finishing up" tail: the scan's Completed event has cleared granular progress while
            // books reconcile into Room. Show only the loader, headline and an indeterminate bar —
            // never a reset-looking 0/0 count, empty stats and a bar pinned at zero.
            ScanHeader(wide = wide, subtitle = "Finishing up — almost there.")
            Spacer(Modifier.height(26.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val subtitle =
                if (progress.phase == "persisting") {
                    "Saving your library — almost done."
                } else {
                    "Analyzing your audiobooks — this'll just take a moment."
                }
            ScanHeader(wide = wide, subtitle = subtitle)
            Spacer(Modifier.height(26.dp))
            ScanProgressBlock(progress = progress)
            Spacer(Modifier.height(26.dp))
            ScanStatsBlock(progress = progress, wide = wide)
        }
        if (stalled) {
            Spacer(Modifier.height(30.dp))
            StalledEscape(onContinue = onContinue)
        }
    }
}

/**
 * Never-stranded escape shown when the scan has stalled: an honest message, a primary "Continue"
 * into the partial library, and a hint pointing at the existing manual rescan in Settings.
 */
@Composable
private fun StalledEscape(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.library_scan_stalled_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(16.dp))
        ListenUpButton(
            text = stringResource(Res.string.library_scan_continue),
            onClick = onContinue,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(Res.string.library_scan_stalled_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun ScanHeader(
    wide: Boolean,
    subtitle: String,
) {
    ScanLoader(size = if (wide) 158.dp else 146.dp)
    Spacer(Modifier.height(26.dp))
    Text(
        text = stringResource(Res.string.library_setup_building_title),
        style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1).sp,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 360.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ScanProgressBlock(progress: ScanProgressState) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = progress.current.toString(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.scan_files_total, progress.filesTotal),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(14.dp))
    val fraction = progress.progressFraction
    if (fraction != null) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(12.dp))
    ScanProgressLabel(progress = progress)
}

/**
 * The percent/ETA label, isolated so only it recomposes on each [ETA_TICK_MS] clock tick — the
 * count row and wavy bar above it stay still.
 */
@Composable
@OptIn(ExperimentalTime::class)
private fun ScanProgressLabel(progress: ScanProgressState) {
    val nowMs by produceState(initialValue = Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds()
            delay(ETA_TICK_MS)
        }
    }
    val fraction = progress.progressFraction
    if (fraction == null) {
        Text(
            text = progress.phaseDisplayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (progress.phase == "persisting") {
        // The persist phase has no meaningful per-scan ETA (the bar restarts at 0 after analysis
        // already elapsed), so show the honest "Saving N of M" count instead of a bogus time.
        Text(
            text = progress.savingLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val pct = (fraction * 100).roundToInt()
    val eta = etaMinutes(nowMs - progress.startedAtMs, fraction)
    Text(
        text =
            if (eta != null) {
                stringResource(Res.string.library_scan_progress_percent_eta, pct, eta)
            } else {
                stringResource(Res.string.library_scan_progress_percent, pct)
            },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScanStatsBlock(
    progress: ScanProgressState,
    wide: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatChip(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            value = progress.books,
            label = "Books",
            modifier = Modifier.weight(1f),
        )
        StatChip(
            icon = Icons.Rounded.Person,
            value = progress.authors,
            label = "Authors",
            modifier = Modifier.weight(1f),
        )
        StatChip(
            icon = Icons.Rounded.Schedule,
            value = progress.hours,
            label = "Hours",
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(22.dp))
    Text(
        text = stringResource(Res.string.scan_recently_matched),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    ScanCoversMarquee(
        books = progress.recentBooks,
        tile = if (wide) 58.dp else 54.dp,
    )
    Spacer(Modifier.height(18.dp))
    ScanFileLine(file = progress.currentFile)
}
