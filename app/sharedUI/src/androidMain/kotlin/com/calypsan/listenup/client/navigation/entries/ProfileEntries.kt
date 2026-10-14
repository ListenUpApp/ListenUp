package com.calypsan.listenup.client.navigation.entries

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.popFrom
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.CreateShelf
import com.calypsan.listenup.client.navigation.EditProfile
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import com.calypsan.listenup.client.util.decodePickedImageAsJpeg
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

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
                backStack.popFrom(args)
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
                        val bytes = decodePickedImageAsJpeg(context, selectedUri, AVATAR_QUALITY)
                        if (bytes != null) {
                            viewModel.stageAvatarUpload(bytes, "image/jpeg", frame = null)
                        }
                        // Silently drop failed compressions — user can retry by picking again.
                    }
                }
            }

        com.calypsan.listenup.client.features.profile.EditProfileScreen(
            onBack = {
                backStack.popFrom(EditProfile)
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

private const val AVATAR_QUALITY = 85
