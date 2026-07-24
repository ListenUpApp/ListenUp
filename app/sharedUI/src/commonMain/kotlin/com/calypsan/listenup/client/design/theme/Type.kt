package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * ListenUp typography system.
 *
 * Platform-specific implementations:
 * - Android: Uses Google Sans Flex variable font for expressive typography
 * - Desktop: Uses system sans-serif font for native platform feel
 */
expect val ListenUpTypography: Typography

/**
 * Display font family for hero text and editorial-style headlines.
 *
 * Platform-specific implementations:
 * - Android: Google Sans Flex with condensed width (95f) and semibold weight
 * - Desktop: System sans-serif (matches platform conventions)
 */
expect val DisplayFontFamily: FontFamily
