package com.calypsan.listenup.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.design.timeline.TimelinePreviewGallery
import com.calypsan.listenup.client.features.auth.PendingApprovalContent
import com.calypsan.listenup.client.features.bookdetail.BookDetailPreviewGallery
import com.calypsan.listenup.client.features.home.HomePreviewGallery
import com.calypsan.listenup.client.features.nowplaying.NowPlayingPreviewGallery
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState

/**
 * Debug-only on-device gallery of a feature's components rendered with mock data, with dynamic
 * color off so the designed fallback palette shows. Not in the launcher.
 *
 * Defaults to the Home gallery; pass `--es gallery <name>` to render a specific feature:
 * ```
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity --es gallery bookdetail
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity --es gallery nowplaying
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity --es gallery timeline
 * ```
 */
class PreviewGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val gallery = intent.getStringExtra("gallery")
        setContent {
            ListenUpTheme(dynamicColor = false) {
                when (gallery) {
                    "bookdetail" -> {
                        BookDetailPreviewGallery()
                    }

                    "nowplaying" -> {
                        NowPlayingPreviewGallery()
                    }

                    "timeline" -> {
                        TimelinePreviewGallery()
                    }

                    "pendingapproval" -> {
                        PendingApprovalContent(
                            state = PendingApprovalUiState.Waiting,
                            email = "newreader@example.com",
                            onCheckStatus = {},
                            onSignIn = {},
                            onCancel = {},
                        )
                    }

                    else -> {
                        HomePreviewGallery()
                    }
                }
            }
        }
    }
}
