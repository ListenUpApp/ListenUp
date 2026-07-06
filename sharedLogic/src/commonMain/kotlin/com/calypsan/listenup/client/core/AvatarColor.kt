package com.calypsan.listenup.client.core

import kotlin.uuid.Uuid

/**
 * Stable, per-user avatar background as a hex string, mirroring the UI-layer palette in
 * `design/components/UserAvatar.kt` so generated colors stay consistent app-wide.
 *
 * Pure and deterministic: a valid UUID hashes its most-significant bits into the palette;
 * any other string falls back to a length-based index. Shared by presentation (profile
 * header) and the data layer (activity feed and active-session mirrors, which synthesize
 * the color because the wire DTOs carry none).
 */
fun stableAvatarColorHex(userId: String): String {
    val index =
        try {
            Uuid.parse(userId).toLongs { msb, _ -> msb.hashCode() }.mod(AVATAR_PALETTE.size)
        } catch (_: IllegalArgumentException) {
            userId.length.mod(AVATAR_PALETTE.size)
        }
    return AVATAR_PALETTE[index]
}

private val AVATAR_PALETTE =
    listOf(
        "#E53935",
        "#D81B60",
        "#8E24AA",
        "#5E35B1",
        "#3949AB",
        "#1E88E5",
        "#039BE5",
        "#00ACC1",
        "#00897B",
        "#43A047",
        "#FB8C00",
        "#6D4C41",
    )
