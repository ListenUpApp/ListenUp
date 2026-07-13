package com.calypsan.listenup.client.design.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * On-device gallery of [MarkerLaneTimeline] rendered with mock data, following the
 * `BookDetailPreviewGallery`/`NowPlayingPreviewGallery` shape. Registered in
 * `PreviewGalleryActivity` under `--es gallery timeline`.
 */
@Composable
fun TimelinePreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            SingleLaneSection()
            TwoLaneStackSection()
            DenseMarkersSection()
            ZoomedInSection()
        }
    }
}

private val mockDurationMs = 3_600_000L // 1 hour

@Composable
private fun mockStylesResolved(): Map<String, MarkerStyle> =
    mapOf(
        "default" to MarkerStyle(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
    )

/** A no-op display-only lane policy — the gallery never negotiates real drags. */
private class DisplayOnlyPolicy : LanePolicy {
    override fun canDrag(marker: TimeMarker) = true

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ) = proposedMs

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) = Unit
}

private class MockMarkerLane(
    markers: List<TimeMarker>,
) : MarkerLane {
    override val markers = MutableStateFlow(markers)
    override val policy: LanePolicy = DisplayOnlyPolicy()
}

@Composable
private fun SingleLaneSection() {
    GalleryLabel("Single lane — a handful of markers")
    val lane =
        remember {
            MockMarkerLane(
                listOf(
                    TimeMarker(id = "m1", timeMs = 300_000L, label = "Chapter 1", styleKey = "default"),
                    TimeMarker(id = "m2", timeMs = 900_000L, label = "Chapter 2", styleKey = "default"),
                    TimeMarker(id = "m3", timeMs = 1_800_000L, label = "Chapter 3", styleKey = "default"),
                ),
            )
        }
    MarkerLaneTimeline(
        lanes = listOf(lane),
        durationMs = mockDurationMs,
        playheadMs = { 900_000L },
        onSeek = {},
        styles = mockStylesResolved(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

@Composable
private fun TwoLaneStackSection() {
    GalleryLabel("Two-lane stack")
    val chapterLane =
        remember {
            MockMarkerLane(
                listOf(
                    TimeMarker(id = "c1", timeMs = 300_000L, label = "Chapter 1", styleKey = "default"),
                    TimeMarker(id = "c2", timeMs = 1_500_000L, label = "Chapter 2", styleKey = "default"),
                ),
            )
        }
    val fileLane =
        remember {
            MockMarkerLane(
                listOf(
                    TimeMarker(id = "f1", timeMs = 0L, label = "File 1", styleKey = "default"),
                    TimeMarker(id = "f2", timeMs = 1_800_000L, label = "File 2", styleKey = "default"),
                ),
            )
        }
    MarkerLaneTimeline(
        lanes = listOf(chapterLane, fileLane),
        durationMs = mockDurationMs,
        playheadMs = { 600_000L },
        onSeek = {},
        styles = mockStylesResolved(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

@Composable
private fun DenseMarkersSection() {
    GalleryLabel("Dense markers (20+, proves overlap rendering under contiguity)")
    val lane =
        remember {
            val markers =
                (0 until 25).map { i ->
                    TimeMarker(
                        id = "d$i",
                        timeMs = (i * (mockDurationMs / 25)),
                        label = "Marker $i",
                        styleKey = "default",
                    )
                }
            MockMarkerLane(markers)
        }
    MarkerLaneTimeline(
        lanes = listOf(lane),
        durationMs = mockDurationMs,
        playheadMs = { 0L },
        onSeek = {},
        styles = mockStylesResolved(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

@Composable
private fun ZoomedInSection() {
    GalleryLabel("Zoomed-in view")
    val lane =
        remember {
            MockMarkerLane(
                listOf(
                    TimeMarker(id = "z1", timeMs = 300_000L, label = "Chapter 1", styleKey = "default"),
                    TimeMarker(id = "z2", timeMs = 450_000L, label = "Chapter 2", styleKey = "default"),
                ),
            )
        }
    val state = rememberTimelineState()
    LaunchedEffect(state) { state.applyGeometry(state.geometryFor(mockDurationMs).copy(zoom = 6f)) }
    MarkerLaneTimeline(
        lanes = listOf(lane),
        durationMs = mockDurationMs,
        playheadMs = { 350_000L },
        onSeek = {},
        styles = mockStylesResolved(),
        state = state,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
