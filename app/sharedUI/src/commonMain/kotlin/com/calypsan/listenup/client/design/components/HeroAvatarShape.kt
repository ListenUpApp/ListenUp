@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * The signature M3 Expressive "cookie" scallop — a 9-sided rounded star. Backed by
 * [MaterialShapes.Cookie9Sided]; [toShape] remembers the result across compositions.
 * Reuse this anywhere the app needs the scallop (avatars, celebratory badges).
 */
@Composable
fun cookieScallopShape(): Shape = MaterialShapes.Cookie9Sided.toShape()
