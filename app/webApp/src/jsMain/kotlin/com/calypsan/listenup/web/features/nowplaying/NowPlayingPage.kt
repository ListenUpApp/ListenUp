package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Text

/**
 * `/playing` (S4): Now Playing as a page. With nothing loaded it goes Home, replacing the entry; while the loaded book
 * is still being read back from storage it draws nothing rather than bouncing (the cold-start race: the player's state
 * starts `NothingLoaded` before the restore answers).
 */
@Composable
fun NowPlayingRoute(
    router: Router,
    playback: PlaybackSession,
    playingReturn: PlayingReturn,
) {
    val loadedBook by remember(playback) { playback.loadedBook }.collectAsState(initial = LoadedBook.Restoring)
    val state by playback.state.collectAsState()
    LaunchedEffect(loadedBook) {
        if (loadedBook == LoadedBook.NothingLoaded) router.replace(Routes.home)
    }
    val loaded = state as? PlayerUiState.Loaded ?: return
    Div(attrs = { classes("np-page") }) {
        H1(attrs = { classes("np-h1") }) { Text(NOW_PLAYING) }
    }
}

private const val NOW_PLAYING = "Now playing"
