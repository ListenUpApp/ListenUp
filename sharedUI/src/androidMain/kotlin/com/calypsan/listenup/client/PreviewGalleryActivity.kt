package com.calypsan.listenup.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.home.HomePreviewGallery

/**
 * Debug-only on-device gallery of the Home components rendered with mock data, with dynamic color
 * off so the designed fallback palette shows. Not in the launcher; start via
 * `adb shell am start -n com.calypsan.listenup.client/.PreviewGalleryActivity`.
 */
class PreviewGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ListenUpTheme(dynamicColor = false) {
                HomePreviewGallery()
            }
        }
    }
}
