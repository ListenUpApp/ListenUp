package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.player.AudioSource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_np_status_downloaded
import listenup.composeapp.generated.resources.player_np_status_downloaded_offline
import listenup.composeapp.generated.resources.player_np_status_not_playable
import listenup.composeapp.generated.resources.player_np_status_offline
import listenup.composeapp.generated.resources.player_np_status_playing_on
import listenup.composeapp.generated.resources.player_np_status_server_unreachable
import listenup.composeapp.generated.resources.player_np_status_streaming
import listenup.composeapp.generated.resources.player_np_status_streaming_transcoded
import org.jetbrains.compose.resources.stringResource

/** The status slot's words for this source (§3.1). */
@Composable
internal fun AudioSource.statusText(): String =
    when (this) {
        AudioSource.Downloaded -> stringResource(Res.string.player_np_status_downloaded)
        AudioSource.DownloadedOffline -> stringResource(Res.string.player_np_status_downloaded_offline)
        AudioSource.Streaming -> stringResource(Res.string.player_np_status_streaming)
        AudioSource.StreamingTranscoded -> stringResource(Res.string.player_np_status_streaming_transcoded)
        AudioSource.Offline -> stringResource(Res.string.player_np_status_offline)
        AudioSource.ServerUnreachable -> stringResource(Res.string.player_np_status_server_unreachable)
        is AudioSource.Casting -> stringResource(Res.string.player_np_status_playing_on, deviceName)
        AudioSource.NotPlayable -> stringResource(Res.string.player_np_status_not_playable)
    }
