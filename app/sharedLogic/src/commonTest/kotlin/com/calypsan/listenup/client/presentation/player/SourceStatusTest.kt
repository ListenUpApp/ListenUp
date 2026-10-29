package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SourceStatusTest :
    FunSpec({
        test("all is well: downloaded, streaming, and downloaded while offline say nothing") {
            sourceStatusOf(AudioSource.Downloaded) shouldBe null
            sourceStatusOf(AudioSource.Streaming) shouldBe null
            sourceStatusOf(AudioSource.DownloadedOffline) shouldBe null
        }

        test("casting says nothing here: the Cast or AirPlay control names the device") {
            sourceStatusOf(AudioSource.Casting("Living Room")) shouldBe null
        }

        test("news shows: offline, the browser's unreachable, transcoded, not playable") {
            sourceStatusOf(AudioSource.Offline) shouldBe SourceStatus.Offline
            sourceStatusOf(AudioSource.ServerUnreachable) shouldBe SourceStatus.ServerUnreachable
            sourceStatusOf(AudioSource.StreamingTranscoded) shouldBe SourceStatus.StreamingTranscoded
            sourceStatusOf(AudioSource.NotPlayable) shouldBe SourceStatus.NotPlayable
        }

        test("a loaded state reads its status off its source") {
            loaded(source = AudioSource.Offline).status shouldBe SourceStatus.Offline
            loaded(source = AudioSource.Downloaded).status shouldBe null
        }
    })

private fun loaded(source: AudioSource): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book = PlayerBook("b1", "The Way of Kings", emptyList(), emptyList(), null, null, emptyList(), 163_800_000, null),
        phase = PlayerPhase.Playing,
        chapter = PlayerChapter(5, 6, 75, "Bridge Four", isGenericTitle = false, isWholeBook = false, startMs = 0, durationMs = 1_235_000),
        chapters = emptyList(),
        source = source,
        settings = PlayerSettings(1f, 1f, 0f, 0f, 10, 30),
        sleepTimer = SleepTimerState.Inactive,
        primary = PrimaryControl.Pause,
        showsSkipBack = true,
        canSkipToNextChapter = true,
        finished = null,
    )
