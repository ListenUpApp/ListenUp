package com.calypsan.listenup.client.navigation.entries

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.CreateShelf
import com.calypsan.listenup.client.navigation.EditProfile
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import java.io.ByteArrayOutputStream

/** User profile navigation entries. */
internal fun EntryProviderScope<NavKey>.profileEntries(
    backStack: NavBackStack<NavKey>,
    profileRefreshKey: Int,
    onProfileRefreshed: () -> Unit,
) {
    entry<UserProfile> { args ->
        com.calypsan.listenup.client.features.profile.UserProfileScreen(
            userId = args.userId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onEditClick = {
                backStack.add(EditProfile)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onShelfClick = { shelfId ->
                backStack.add(ShelfDetail(shelfId))
            },
            onCreateShelfClick = {
                backStack.add(CreateShelf)
            },
            refreshKey = profileRefreshKey,
        )
    }
    entry<EditProfile> {
        // Hoist the VM so both the screen and the photo picker share the same instance.
        val viewModel: EditProfileViewModel = koinViewModel()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val imagePicker =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia(),
            ) { uri: Uri? ->
                uri?.let { selectedUri ->
                    scope.launch {
                        val bytes = compressAvatar(context, selectedUri)
                        if (bytes != null) {
                            viewModel.stageAvatarUpload(bytes, "image/jpeg")
                        }
                        // Silently drop failed compressions — user can retry by picking again.
                    }
                }
            }

        com.calypsan.listenup.client.features.profile.EditProfileScreen(
            onBack = {
                backStack.removeAt(backStack.lastIndex)
                onProfileRefreshed()
            },
            onPickImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            viewModel = viewModel,
        )
    }
}

// ── Avatar compression helpers ─────────────────────────────────────────────────

private const val MAX_AVATAR_SIZE = 2048
private const val AVATAR_QUALITY = 85

private suspend fun compressAvatar(
    context: Context,
    uri: Uri,
): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (original == null) return@withContext null

            val scale =
                minOf(
                    MAX_AVATAR_SIZE.toFloat() / original.width,
                    MAX_AVATAR_SIZE.toFloat() / original.height,
                    1f,
                )

            val scaled =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).toInt(),
                        (original.height * scale).toInt(),
                        true,
                    )
                } else {
                    original
                }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)

            if (scaled != original) scaled.recycle()
            original.recycle()

            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
