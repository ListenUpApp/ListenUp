@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
)

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
import com.calypsan.listenup.client.design.components.getInitials
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.presentation.profile.EditProfileEvent
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
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
                title = { Text("Edit Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
    onChangePassword: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = ready.user
    val userId = user.id.value

    var editedTagline by rememberSaveable(userId) { mutableStateOf(user.tagline.orEmpty()) }
    var editedFirstName by rememberSaveable(userId) { mutableStateOf(user.firstName.orEmpty()) }
    var editedLastName by rememberSaveable(userId) { mutableStateOf(user.lastName.orEmpty()) }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }

    val hasTaglineChanged = editedTagline != user.tagline.orEmpty()
    val hasNameChanged =
        editedFirstName != user.firstName.orEmpty() || editedLastName != user.lastName.orEmpty()
    val isPasswordValid = newPassword.length >= PASSWORD_MIN
    val passwordsMatch = newPassword == confirmPassword
    val canSavePassword = newPassword.isNotEmpty() && passwordsMatch && isPasswordValid

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
            newPassword = newPassword,
            onNewPasswordChange = { newPassword = it },
            confirmPassword = confirmPassword,
            onConfirmPasswordChange = { confirmPassword = it },
            isPasswordValid = isPasswordValid,
            passwordsMatch = passwordsMatch,
            canSave = canSavePassword,
            isSaving = ready.isSaving,
            onChangePassword = {
                onChangePassword(newPassword)
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
        text = "Avatar",
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
                contentDescription = "Current avatar",
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
                    text = getInitials(user.displayName),
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
                Text("Upload Photo")
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
                    Text("Remove Photo")
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
        text = "Tagline",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "A short bio that appears on your profile",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("What are you listening to?") },
        singleLine = true,
        supportingText = {
            Text(
                text = "${value.length}/${EditProfileViewModel.MAX_TAGLINE_LENGTH}",
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
        Text("Save Tagline")
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
        text = "Name",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Update your first and last name",
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
        Text("Save Name")
    }
}

@Composable
private fun PasswordSection(
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
        text = "Change Password",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Set a new password for your account",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = newPassword,
        onValueChange = onNewPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("New Password") },
        placeholder = { Text("Enter new password") },
        singleLine = true,
        enabled = !isSaving,
        visualTransformation =
            if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                Icon(
                    imageVector =
                        if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (newPasswordVisible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = {
            if (newPassword.isNotEmpty() && !isPasswordValid) {
                Text(
                    text = "Must be at least ${PASSWORD_MIN} characters",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = confirmPassword,
        onValueChange = onConfirmPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Confirm Password") },
        placeholder = { Text("Re-enter new password") },
        singleLine = true,
        enabled = !isSaving,
        visualTransformation =
            if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                Icon(
                    imageVector =
                        if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription =
                        if (confirmPasswordVisible) "Hide password" else "Show password",
                )
            }
        },
        supportingText = {
            if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                Text(
                    text = "Passwords do not match",
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
        Text("Change Password")
    }
}

private const val MAX_AVATAR_SIZE = 2048
private const val AVATAR_QUALITY = 85

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
