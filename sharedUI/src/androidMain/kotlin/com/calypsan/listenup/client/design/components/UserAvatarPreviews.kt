package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

private const val PREVIEW_UUID = "550e8400-e29b-41d4-a716-446655440000"

@Preview
@Composable
private fun UserAvatarMiniPreview() {
    UserAvatar(userId = PREVIEW_UUID, size = AvatarSize.Mini)
}

@Preview
@Composable
private fun UserAvatarSmallPreview() {
    UserAvatar(userId = PREVIEW_UUID, size = AvatarSize.Small)
}

@Preview
@Composable
private fun UserAvatarMediumPreview() {
    UserAvatar(userId = PREVIEW_UUID, size = AvatarSize.Medium)
}

@Preview
@Composable
private fun UserAvatarLargePreview() {
    UserAvatar(userId = PREVIEW_UUID, size = AvatarSize.Large)
}

@Preview
@Composable
private fun UserAvatarLargeClickablePreview() {
    UserAvatar(userId = PREVIEW_UUID, size = AvatarSize.Large, onClick = {})
}
