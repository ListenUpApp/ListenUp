package com.calypsan.listenup.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.bookdetail.BookDetailPreviewGallery
import com.calypsan.listenup.client.features.home.HomePreviewGallery

/**
 * Debug-only on-device gallery of a feature's components rendered with mock data, with dynamic
 * color off so the designed fallback palette shows. Not in the launcher.
 *
 * Defaults to the Home gallery; pass `--es gallery bookdetail` to render the Book Detail gallery:
 * ```
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity
 * adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity --es gallery bookdetail
 * ```
 */
class PreviewGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val gallery = intent.getStringExtra("gallery")
        setContent {
            ListenUpTheme(dynamicColor = false) {
                if (gallery == "bookdetail") {
                    BookDetailPreviewGallery()
                } else {
                    HomePreviewGallery()
                }
            }
        }
    }
}
