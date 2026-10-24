package com.calypsan.listenup.client.features.nowplaying.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Date

/** Android's own time format: it follows Settings › Use 24-hour format and the device locale. */
@Composable
actual fun rememberClockTimeFormatter(): (epochMs: Long) -> String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return remember(format) { { epochMs -> format.format(Date(epochMs)) } }
}
