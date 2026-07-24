package com.calypsan.listenup.client.shortcuts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.repository.HomeRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for [ListenUpShortcutManager.calculateSampleSize] and
 * [ListenUpShortcutManager.createSquareBitmap].
 *
 * [BitmapFactory.Options] is a plain field-holder that could in principle be tested
 * without Robolectric, but [createSquareBitmap] calls [Bitmap.createBitmap] and
 * [Bitmap.createScaledBitmap] which invoke native code shadowed by Robolectric.
 * Using a single [RobolectricTestRunner] class covers both helpers in the same file
 * to avoid an awkward test-runner split. This is consistent with the [DeepLinkParserTest]
 * precedent for Android-framework-touching unit logic.
 *
 * Both functions are widened to `internal` in the production class solely for testing;
 * their "Visible for testing" KDoc marks the intent.
 */
@RunWith(RobolectricTestRunner::class)
class ListenUpShortcutManagerTest {
    private val manager: ListenUpShortcutManager by lazy {
        ListenUpShortcutManager(
            context = RuntimeEnvironment.getApplication(),
            homeRepository =
                object : HomeRepository {
                    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> = AppResult.Success(emptyList())

                    override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = flowOf(emptyList())
                },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    // ──────────────────────────── cancellation contract ────────────────────────────

    @Test
    fun `updateShortcutsInternal re-throws CancellationException instead of swallowing it`() =
        runTest {
            val cancellingManager =
                ListenUpShortcutManager(
                    context = RuntimeEnvironment.getApplication(),
                    homeRepository =
                        object : HomeRepository {
                            override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningBook>> = throw CancellationException("shortcut refresh cancelled")

                            override fun observeContinueListening(limit: Int): Flow<List<ContinueListeningItem>> = flowOf(emptyList())
                        },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            shouldThrow<CancellationException> { cancellingManager.updateShortcutsInternal() }
        }

    // ───────────────────────────── calculateSampleSize ──────────────────────────────

    @Test
    fun `calculateSampleSize returns 1 when source equals target`() {
        val options = options(width = 128, height = 128)
        manager.calculateSampleSize(options, 128, 128) shouldBe 1
    }

    @Test
    fun `calculateSampleSize returns 1 when source is smaller than target`() {
        val options = options(width = 64, height = 64)
        manager.calculateSampleSize(options, 128, 128) shouldBe 1
    }

    @Test
    fun `calculateSampleSize returns 1 when source is slightly larger than target but under 2x`() {
        // halfWidth = 100, halfHeight = 100 — both less than 128, so loop never enters
        val options = options(width = 200, height = 200)
        manager.calculateSampleSize(options, 128, 128) shouldBe 1
    }

    @Test
    fun `calculateSampleSize returns 2 when source is exactly 2x target`() {
        // halfWidth = 128 >= 128, loop enters once; 128/2 = 64 < 128, exits → sampleSize 2
        val options = options(width = 256, height = 256)
        manager.calculateSampleSize(options, 128, 128) shouldBe 2
    }

    @Test
    fun `calculateSampleSize returns 4 when source is exactly 4x target`() {
        val options = options(width = 512, height = 512)
        manager.calculateSampleSize(options, 128, 128) shouldBe 4
    }

    @Test
    fun `calculateSampleSize returns 8 when source is exactly 8x target`() {
        val options = options(width = 1024, height = 1024)
        manager.calculateSampleSize(options, 128, 128) shouldBe 8
    }

    @Test
    fun `calculateSampleSize returns 2 for a non-power-of-two ratio between 2x and 4x`() {
        // 384 / 128 = 3x; halfWidth=192 >= 128 → sampleSize *= 2; halfWidth/2=96 < 128 → exit
        val options = options(width = 384, height = 384)
        manager.calculateSampleSize(options, 128, 128) shouldBe 2
    }

    @Test
    fun `calculateSampleSize uses the shorter dimension as the bottleneck for portrait source`() {
        // width=400 is the bottleneck (halfWidth=200 >= 128 but halfWidth/2=100 < 128) → 2
        val options = options(width = 400, height = 1024)
        manager.calculateSampleSize(options, 128, 128) shouldBe 2
    }

    @Test
    fun `calculateSampleSize uses the shorter dimension as the bottleneck for landscape source`() {
        // height=400 is the bottleneck → same logic as portrait case → 2
        val options = options(width = 1024, height = 400)
        manager.calculateSampleSize(options, 128, 128) shouldBe 2
    }

    @Test
    fun `calculateSampleSize does not infinite-loop when outWidth and outHeight are zero`() {
        // Zero dimensions: outer if-guard is false → returns 1 immediately
        val options = BitmapFactory.Options()
        manager.calculateSampleSize(options, 128, 128) shouldBe 1
    }

    @Test
    fun `calculateSampleSize result is always a power of two for a range of source sizes`() {
        val targetSize = 128
        val sourceSizes = listOf(100, 256, 384, 512, 768, 1024, 2048, 3000, 4096)
        for (size in sourceSizes) {
            val result = manager.calculateSampleSize(options(size, size), targetSize, targetSize)
            // A power of two satisfies: result > 0 and exactly one bit set
            (result > 0 && result and (result - 1) == 0) shouldBe true
        }
    }

    // ──────────────────────────── createSquareBitmap ──────────────────────────────

    @Test
    fun `createSquareBitmap on a square source returns a bitmap of exactly targetSize`() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val result = manager.createSquareBitmap(source, 128)
        result.width shouldBe 128
        result.height shouldBe 128
    }

    @Test
    fun `createSquareBitmap on a portrait source crops to square first`() {
        // Taller than wide; the crop side is min(256, 512) = 256, centred vertically
        val source = Bitmap.createBitmap(256, 512, Bitmap.Config.ARGB_8888)
        val result = manager.createSquareBitmap(source, 128)
        result.width shouldBe 128
        result.height shouldBe 128
    }

    @Test
    fun `createSquareBitmap on a landscape source crops to square`() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val result = manager.createSquareBitmap(source, 128)
        result.width shouldBe 128
        result.height shouldBe 128
    }

    @Test
    fun `createSquareBitmap upscales a source that is smaller than targetSize`() {
        // 64×64 < 128 target: createBitmap crops to 64×64 (width == targetSize? no), so scales
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val result = manager.createSquareBitmap(source, 128)
        result.width shouldBe 128
        result.height shouldBe 128
    }

    @Test
    fun `createSquareBitmap returns non-null for any valid source`() {
        val source = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        manager.createSquareBitmap(source, 128) shouldNotBe null
    }

    @Test
    fun `createSquareBitmap skips the scale step when cropped size already equals targetSize`() {
        // 128×128 source: min side = 128, crop starts at (0,0) 128×128. Width equals targetSize
        // so the `else` branch is taken and the cropped bitmap is returned directly.
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val result = manager.createSquareBitmap(source, 128)
        result.width shouldBe 128
        result.height shouldBe 128
    }

    // ──────────────────────────────── helpers ─────────────────────────────────────

    private fun options(
        width: Int,
        height: Int,
    ) = BitmapFactory.Options().apply {
        outWidth = width
        outHeight = height
    }
}
