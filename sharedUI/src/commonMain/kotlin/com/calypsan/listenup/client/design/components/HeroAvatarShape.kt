@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * The signature M3 Expressive avatar shape — a 9-sided "cookie" scallop — used for the
 * contributor hero avatar in place of a plain circle.
 *
 * Backed by [MaterialShapes.Cookie9Sided]; the returned [Shape] is remembered across
 * compositions by [toShape].
 */
@Composable
fun contributorAvatarShape(): Shape = MaterialShapes.Cookie9Sided.toShape()
