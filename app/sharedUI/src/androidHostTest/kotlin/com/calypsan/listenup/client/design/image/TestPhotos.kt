package com.calypsan.listenup.client.design.image

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import org.robolectric.RuntimeEnvironment
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.File

/** A real [width]×[height] PNG, so a test under native graphics decodes an actual photo. */
internal fun pngBytes(
    width: Int = 30,
    height: Int = 40,
): ByteArray =
    ByteArrayOutputStream()
        .also { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()

/** [pngBytes] written to a temporary file, deleted when the JVM exits. */
internal fun pngFile(
    width: Int = 30,
    height: Int = 40,
): File =
    File.createTempFile("photo", ".png").apply {
        deleteOnExit()
        writeBytes(pngBytes(width, height))
    }

/**
 * Starts global Koin with what `ContributorCoverImage` and `rememberContributorImageRequest` resolve
 * through it: every contributor's photo is the cached file at [photoPath]. Stop it in `@After`.
 */
internal fun startKoinForContributorPhotos(photoPath: String) {
    startKoin {
        modules(
            module {
                single<ImageRepository> {
                    mock(MockMode.autoUnit) {
                        every { getContributorImagePath(any()) } returns photoPath
                        every { contributorImageExists(any()) } returns true
                    }
                }
                single<ServerConfig> { mock(MockMode.autoUnit) }
                single<AuthSession> { mock(MockMode.autoUnit) }
            },
        )
    }
}

/**
 * Robolectric's native graphics cannot run `ImageDecoder` over a file, which Coil uses for every path it
 * loads; `BitmapFactory` decodes the same file fine. Call before rendering a photo from disk, and
 * [restoreCoil] after.
 */
@OptIn(DelicateCoilApi::class)
internal fun decodePhotosWithBitmapFactory() {
    SingletonImageLoader.setUnsafe(
        ImageLoader.Builder(RuntimeEnvironment.getApplication()).imageDecoderEnabled(false).build(),
    )
}

/** Undoes [decodePhotosWithBitmapFactory]. */
@OptIn(DelicateCoilApi::class)
internal fun restoreCoil() {
    SingletonImageLoader.reset()
}
