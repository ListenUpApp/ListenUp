package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The JVM's localized short time at the system zone. */
@Composable
actual fun rememberClockTimeFormatter(): (epochMs: Long) -> String =
    remember {
        val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
        val formatter: (Long) -> String = { epochMs -> format.format(Instant.ofEpochMilli(epochMs)) }
        formatter
    }
