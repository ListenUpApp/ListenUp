package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_greeting_afternoon
import listenup.composeapp.generated.resources.home_greeting_evening
import listenup.composeapp.generated.resources.home_greeting_lead
import listenup.composeapp.generated.resources.home_greeting_morning
import listenup.composeapp.generated.resources.home_greeting_night
import listenup.composeapp.generated.resources.home_welcome
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Home's greeting (spec §3.1): "Good evening," over the first name as the screen's one display moment. The name is the
 * screen's heading; without a name the greeting stands alone.
 *
 * @param timeOfDay the part of the day the shared state chose (H2).
 * @param firstName the reader's first name, or null.
 * @param isWide true at medium widths and wider, where the name grows to `displayLarge`.
 * @param modifier Optional modifier.
 * @param isWelcome a new listener: "Welcome," in place of the part of the day (spec §2.3 NewListener).
 */
@Composable
fun HomeHeader(
    timeOfDay: TimeOfDay,
    firstName: String?,
    isWide: Boolean,
    modifier: Modifier = Modifier,
    isWelcome: Boolean = false,
) {
    val greeting = stringResource(if (isWelcome) Res.string.home_welcome else timeOfDay.greetingRes())
    Column(modifier = modifier.semantics(mergeDescendants = true) { heading() }) {
        if (firstName.isNullOrBlank()) {
            Text(
                text = greeting,
                style =
                    if (isWide) {
                        MaterialTheme.typography.displayMediumEmphasized
                    } else {
                        MaterialTheme.typography.headlineLargeEmphasized
                    },
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = stringResource(Res.string.home_greeting_lead, greeting),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = firstName,
                style =
                    if (isWide) {
                        MaterialTheme.typography.displayLargeEmphasized
                    } else {
                        MaterialTheme.typography.displayMediumEmphasized
                    },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The catalog words for a part of the day. */
internal fun TimeOfDay.greetingRes(): StringResource =
    when (this) {
        TimeOfDay.Morning -> Res.string.home_greeting_morning
        TimeOfDay.Afternoon -> Res.string.home_greeting_afternoon
        TimeOfDay.Evening -> Res.string.home_greeting_evening
        TimeOfDay.Night -> Res.string.home_greeting_night
    }
