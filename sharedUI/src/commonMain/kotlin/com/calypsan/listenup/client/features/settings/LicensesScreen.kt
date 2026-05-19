@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.settings_listenup_uses_the_following_open
import listenup.composeapp.generated.resources.settings_open_source_licenses

/**
 * Data class representing an open source library.
 */
private data class Library(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
)

/**
 * List of open source libraries used by the app.
 */
private val libraries =
    listOf(
        Library(
            name = "Jetpack Compose",
            author = "Google",
            license = "Apache 2.0",
            url = "https://developer.android.com/jetpack/compose",
        ),
        Library(
            name = "Material 3",
            author = "Google",
            license = "Apache 2.0",
            url = "https://m3.material.io",
        ),
        Library(
            name = "Kotlin",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://kotlinlang.org",
        ),
        Library(
            name = "Kotlin Coroutines",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        Library(
            name = "Ktor Client",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://ktor.io",
        ),
        Library(
            name = "Koin",
            author = "InsertKoinIO",
            license = "Apache 2.0",
            url = "https://insert-koin.io",
        ),
        Library(
            name = "Media3 / ExoPlayer",
            author = "Google",
            license = "Apache 2.0",
            url = "https://developer.android.com/media/media3",
        ),
        Library(
            name = "Coil",
            author = "Coil Contributors",
            license = "Apache 2.0",
            url = "https://coil-kt.github.io/coil",
        ),
        Library(
            name = "Navigation 3",
            author = "Google",
            license = "Apache 2.0",
            url = "https://developer.android.com/guide/navigation",
        ),
        Library(
            name = "WorkManager",
            author = "Google",
            license = "Apache 2.0",
            url = "https://developer.android.com/topic/libraries/architecture/workmanager",
        ),
        Library(
            name = "Kotlin Logging",
            author = "oshai",
            license = "Apache 2.0",
            url = "https://github.com/oshai/kotlin-logging",
        ),
        Library(
            name = "Multiplatform Markdown Renderer",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://github.com/JetBrains/markdown",
        ),
        Library(
            name = "BlurHash",
            author = "Wolt Enterprises",
            license = "MIT",
            url = "https://github.com/woltapp/blurhash",
        ),
        Library(
            name = "kotlinx-datetime",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://github.com/Kotlin/kotlinx-datetime",
        ),
        Library(
            name = "kotlinx-serialization",
            author = "JetBrains",
            license = "Apache 2.0",
            url = "https://github.com/Kotlin/kotlinx.serialization",
        ),
    )

/**
 * Screen displaying open source licenses for third-party libraries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_open_source_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.settings_listenup_uses_the_following_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            items(libraries) { library ->
                LibraryCard(library = library)
            }
        }
    }
}

@Composable
private fun LibraryCard(library: Library) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "by ${library.author}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = library.license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
