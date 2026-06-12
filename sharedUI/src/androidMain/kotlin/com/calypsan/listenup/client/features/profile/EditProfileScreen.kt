
package com.calypsan.listenup.client.features.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.calypsan.listenup.client.design.components.ListenUpTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator

import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_name
import listenup.composeapp.generated.resources.profile_avatar
import listenup.composeapp.generated.resources.profile_change_password
import listenup.composeapp.generated.resources.profile_current_avatar
import listenup.composeapp.generated.resources.profile_edit_profile_title
import listenup.composeapp.generated.resources.profile_name_description
import listenup.composeapp.generated.resources.profile_password_description
import listenup.composeapp.generated.resources.profile_password_min_chars
import listenup.composeapp.generated.resources.profile_passwords_do_not_match
import listenup.composeapp.generated.resources.profile_remove_photo
import listenup.composeapp.generated.resources.profile_save_name
import listenup.composeapp.generated.resources.profile_save_tagline
import listenup.composeapp.generated.resources.profile_tagline
import listenup.composeapp.generated.resources.profile_tagline_char_count
import listenup.composeapp.generated.resources.profile_tagline_description
import listenup.composeapp.generated.resources.profile_tagline_placeholder
import listenup.composeapp.generated.resources.profile_upload_photo
import java.io.ByteArrayOutputStream
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    onProfileUpdated: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: EditProfileViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            uri?.let { selectedUri ->
                scope.launch {
                    val compressedBytes = compressAvatar(context, selectedUri)
                    if (compressedBytes != null) {
                        viewModel.uploadAvatar(compressedBytes, "image/jpeg")
                    } else {
                        snackbarHostState.showSnackbar("Failed to process image")
                    }
                }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditProfileEvent.TaglineSaved,
                EditProfileEvent.NameSaved,
                EditProfileEvent.AvatarUpdated,
                -> {
                    snackbarHostState.showSnackbar("Profile updated")
                    onProfileUpdated()
                }

                EditProfileEvent.PasswordChanged -> {
                    snackbarHostState.showSnackbar("Password changed successfully")
                }

                is EditProfileEvent.SaveFailed -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profile_edit_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val current = state) {
                is EditProfileUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is EditProfileUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is EditProfileUiState.Ready -> {
                    EditProfileContent(
                        ready = current,
                        onSaveTagline = viewModel::saveTagline,
                        onUploadAvatar = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRevertAvatar = viewModel::revertToAutoAvatar,
                        onSaveName = viewModel::saveName,
                        onChangePassword = viewModel::changePassword,
                    )
                }
            }

            if ((state as? EditProfileUiState.Ready)?.isSaving == true) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicator()
                }
            }
        }
    }
}

@Composable
private fun EditProfileContent(
    ready: EditProfileUiState.Ready,
    onSaveTagline: (String) -> Unit,
    onUploadAvatar: () -> Unit,
    onRevertAvatar: () -> Unit,
    onSaveName: (String, String) -> Unit,
    onChangePassword: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = ready.user
    val userId = user.id.value

    var editedTagline by rememberSaveable(userId) { mutableStateOf(user.tagline.orEmpty()) }
    var editedFirstName by rememberSaveable(userId) { mutableStateOf(user.firstName.orEmpty()) }
    var editedLastName by rememberSaveable(userId) { mutableStateOf(user.lastName.orEmpty()) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    val hasTaglineChanged = editedTagline != user.tagline.orEmpty()
    val hasNameChanged =
        editedFirstName != user.firstName.orEmpty() || editedLastName != user.lastName.orEmpty()
    val isPasswordValid = newPassword.length >= PASSWORD_MIN
    val passwordsMatch = newPassword == confirmPassword
    val canSavePassword = currentPassword.isNotEmpty() && newPassword.isNotEmpty() && passwordsMatch && isPasswordValid

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        AvatarSection(
            user = user,
            localAvatarPath = ready.localAvatarPath,
            isSaving = ready.isSaving,
            onUploadAvatar = onUploadAvatar,
            onRevertAvatar = onRevertAvatar,
        )

        Spacer(modifier = Modifier.height(32.dp))

        TaglineSection(
            value = editedTagline,
            onValueChange = { newValue ->
                editedTagline =
                    if (newValue.length > EditProfileViewModel.MAX_TAGLINE_LENGTH) {
                        newValue.take(EditProfileViewModel.MAX_TAGLINE_LENGTH)
                    } else {
                        newValue
                    }
            },
            hasChanged = hasTaglineChanged,
            isSaving = ready.isSaving,
            onSave = { onSaveTagline(editedTagline) },
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        NameSection(
            firstName = editedFirstName,
            lastName = editedLastName,
            onFirstNameChange = { editedFirstName = it },
            onLastNameChange = { editedLastName = it },
            hasChanged = hasNameChanged,
            isSaving = ready.isSaving,
            onSave = { onSaveName(editedFirstName, editedLastName) },
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        PasswordSection(
            currentPassword = currentPassword,
            onCurrentPasswordChange = { currentPassword = it },
            newPassword = newPassword,
            onNewPasswordChange = { newPassword = it },
            confirmPassword = confirmPassword,
            onConfirmPasswordChange = { confirmPassword = it },
            isPasswordValid = isPasswordValid,
            passwordsMatch = passwordsMatch,
            canSave = canSavePassword,
            isSaving = ready.isSaving,
            onChangePassword = {
                onChangePassword(currentPassword, newPassword)
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
            },
        )
    }
}

@Composable
private fun AvatarSection(
    user: User,
    localAvatarPath: String?,
    isSaving: Boolean,
    onUploadAvatar: () -> Unit,
    onRevertAvatar: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.profile_avatar),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backgroundColor =
            remember(user.avatarColor) {
                try {
                    Color(AndroidColor.parseColor(user.avatarColor))
                } catch (_: IllegalArgumentException) {
                    Color(0xFF6B7280)
                }
            }
        val context = LocalContext.current
        val cacheBuster = user.updatedAtMs

        if (user.hasImageAvatar && localAvatarPath != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(localAvatarPath)
                        .memoryCacheKey("$localAvatarPath-$cacheBuster")
                        .diskCacheKey("$localAvatarPath-$cacheBuster")
                        .build(),
                contentDescription = stringResource(Res.string.profile_current_avatar),
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(backgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        user.displayName
                            .trim()
                            .split("\\s+".toRegex())
                            .let { parts ->
                                when {
                                    parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                                    user.displayName.length >= 2 -> user.displayName.take(2)
                                    else -> user.displayName.take(1)
                                }
                            }.uppercase(),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Button(onClick = onUploadAvatar, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.profile_upload_photo))
            }

            if (user.hasImageAvatar) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onRevertAvatar, enabled = !isSaving) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.profile_remove_photo))
                }
            }
        }
    }
}

@Composable
private fun TaglineSection(
    value: String,
    onValueChange: (String) -> Unit,
    hasChanged: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.profile_tagline),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(Res.string.profile_tagline_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.profile_tagline_placeholder)) },
        singleLine = true,
        supportingText = {
            Text(
                text =
                    stringResource(
                        Res.string.profile_tagline_char_count,
                        value.length,
                        EditProfileViewModel.MAX_TAGLINE_LENGTH,
                    ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (value.length >= EditProfileViewModel.MAX_TAGLINE_LENGTH) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        enabled = !isSaving,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onSave,
        enabled = hasChanged && !isSaving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.profile_save_tagline))
    }
}

@Composable
private fun NameSection(
    firstName: String,
    lastName: String,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    hasChanged: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.common_name),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(Res.string.profile_name_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    ListenUpTextField(
        value = firstName,
        onValueChange = onFirstNameChange,
        label = "First Name",
        placeholder = "Enter your first name",
        enabled = !isSaving,
    )

    Spacer(modifier = Modifier.height(12.dp))

    ListenUpTextField(
        value = lastName,
        onValueChange = onLastNameChange,
        label = "Last Name",
        placeholder = "Enter your last name",
        enabled = !isSaving,
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onSave,
        enabled = hasChanged && !isSaving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.profile_save_name))
    }
}

@Composable
private fun PasswordSection(
    currentPassword: String,
    onCurrentPasswordChange: (String) -> Unit,
    newPassword: String,
    onNewPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    isPasswordValid: Boolean,
    passwordsMatch: Boolean,
    canSave: Boolean,
    isSaving: Boolean,
    onChangePassword: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.profile_change_password),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(Res.string.profile_password_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    PasswordField(
        value = currentPassword,
        onValueChange = onCurrentPasswordChange,
        label = "Current Password",
        placeholder = "Enter current password",
        isSaving = isSaving,
    )

    Spacer(modifier = Modifier.height(12.dp))

    PasswordField(
        value = newPassword,
        onValueChange = onNewPasswordChange,
        label = "New Password",
        placeholder = "Enter new password",
        isSaving = isSaving,
        supportingText = {
            if (newPassword.isNotEmpty() && !isPasswordValid) {
                Text(
                    text = stringResource(Res.string.profile_password_min_chars, PASSWORD_MIN),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    PasswordField(
        value = confirmPassword,
        onValueChange = onConfirmPasswordChange,
        label = "Confirm Password",
        placeholder = "Re-enter new password",
        isSaving = isSaving,
        supportingText = {
            if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                Text(
                    text = stringResource(Res.string.profile_passwords_do_not_match),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onChangePassword,
        enabled = canSave && !isSaving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.profile_change_password))
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isSaving: Boolean,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = !isSaving,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector =
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) PASSWORD_VISIBILITY_HIDE else PASSWORD_VISIBILITY_SHOW,
                )
            }
        },
        supportingText = supportingText,
    )
}

private const val MAX_AVATAR_SIZE = 2048
private const val AVATAR_QUALITY = 85
private const val PASSWORD_VISIBILITY_HIDE = "Hide password"
private const val PASSWORD_VISIBILITY_SHOW = "Show password"

private suspend fun compressAvatar(
    context: Context,
    uri: Uri,
): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap == null) {
                return@withContext null
            }

            val width = originalBitmap.width
            val height = originalBitmap.height
            val scale = minOf(MAX_AVATAR_SIZE.toFloat() / width, MAX_AVATAR_SIZE.toFloat() / height, 1f)

            val scaledWidth = (width * scale).toInt()
            val scaledHeight = (height * scale).toInt()

            val scaledBitmap =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
                } else {
                    originalBitmap
                }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, outputStream)

            if (scaledBitmap != originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap.recycle()

            outputStream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
