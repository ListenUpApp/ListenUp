package com.calypsan.listenup.client.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.notifications.NotificationChannels
import com.calypsan.listenup.client.presentation.player.ChapterName
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The sleep timer in the shade (spec §5.7): a duration timer counts down by itself and offers Add 10 min · Cancel, an
 * end-of-chapter timer names the chapter and offers Cancel, the fade offers Keep listening, and Inactive clears it.
 */
@RunWith(RobolectricTestRunner::class)
class SleepTimerNotifierTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val timer = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    private val chapter = MutableStateFlow<ChapterName?>(ChapterName.Titled("Bridge Four"))
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        NotificationChannels.registerAll(context)
        SleepTimerNotifier(context, timer, chapter, SystemStringsHolder(), scope).start()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun posted(): Notification? = shadowOf(manager).getNotification(SLEEP_NOTIFICATION_ID)

    private fun Notification.actionTitles(): List<String> = actions.orEmpty().map { it.title.toString() }

    private fun duration(
        remainingMs: Long,
        totalMs: Long,
    ) = SleepTimerState.Active(
        mode = SleepTimerMode.Duration(minutes = (totalMs / 60_000).toInt()),
        remainingMs = remainingMs,
        totalMs = totalMs,
        startedAt = STARTED_AT,
    )

    @Test
    fun `a duration timer posts a counting-down notification with Add 10 min and Cancel`() {
        timer.value = duration(remainingMs = 120_000, totalMs = 120_000)

        val notification = posted()!!
        notification.channelId shouldBe NotificationChannels.PLAYBACK
        notification.extras.getBoolean(NotificationCompat.EXTRA_CHRONOMETER_COUNT_DOWN) shouldBe true
        notification.extras.getBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER) shouldBe true
        notification.`when` shouldBe STARTED_AT + 120_000
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString() shouldBe "Sleep timer"
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().startsWith("Pauses at ") shouldBe
            true
        notification.actionTitles() shouldBe listOf("Add 10 min", "Cancel")
        notification.visibility shouldBe Notification.VISIBILITY_PUBLIC
    }

    @Test
    fun `extending moves the deadline in the one notification`() {
        timer.value = duration(remainingMs = 120_000, totalMs = 120_000)
        timer.value = duration(remainingMs = 720_000, totalMs = 720_000)

        shadowOf(manager).allNotifications.size shouldBe 1
        posted()!!.`when` shouldBe STARTED_AT + 720_000
    }

    @Test
    fun `a tick that only counts the remaining time down does not re-post`() {
        timer.value = duration(remainingMs = 120_000, totalMs = 120_000)
        val first = posted()!!

        timer.value = duration(remainingMs = 119_000, totalMs = 120_000)

        // Same deadline, same notice: nothing re-posted, so the very same Notification object is still there.
        (posted() === first) shouldBe true
    }

    @Test
    fun `an end-of-chapter timer names the chapter and offers only Cancel`() {
        timer.value =
            SleepTimerState.Active(
                mode = SleepTimerMode.EndOfChapter,
                remainingMs = 0,
                totalMs = 0,
                startedAt = STARTED_AT,
            )

        val notification = posted()!!
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString() shouldBe
            "Pauses at the end of Bridge Four"
        notification.actionTitles() shouldBe listOf("Cancel")
        notification.extras.getBoolean(NotificationCompat.EXTRA_SHOW_CHRONOMETER) shouldBe false
    }

    @Test
    fun `fading offers Keep listening`() {
        timer.value = SleepTimerState.FadingOut

        val notification = posted()!!
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString() shouldBe "Fading out…"
        notification.actionTitles() shouldBe listOf("Keep listening")
    }

    @Test
    fun `Inactive clears it`() {
        timer.value = duration(remainingMs = 120_000, totalMs = 120_000)
        timer.value = SleepTimerState.Inactive

        posted() shouldBe null
    }

    @Test
    fun `ticks collapse to one notice, and a change of mode does not`() {
        sleepNoticeOf(duration(120_000, 120_000), null) shouldBe sleepNoticeOf(duration(5_000, 120_000), null)
        sleepNoticeOf(SleepTimerState.Inactive, null) shouldBe SleepNotice.Off
        sleepNoticeOf(SleepTimerState.FadingOut, null) shouldBe SleepNotice.Fading
    }

    private companion object {
        const val STARTED_AT = 1_800_000_000_000L
    }
}
