package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.UserAvatar
import org.jetbrains.compose.web.dom.Div

/**
 * One member: who they are, and — in the same page, because there is room — what they may do.
 * The permissions panel is a draft; nothing is sent until Save.
 */
@Composable
fun UserDetailPage(
    state: UserDetailUiState,
    permissions: UserPermissionsUiState,
    actions: PermissionsPanelActions,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("usr") }) {
        Breadcrumb(trail = listOf("People", userCrumb(state)), onNavigate = { onOpenAdmin() })

        when (state) {
            UserDetailUiState.Loading -> {
                PageHeader(title = userCrumb(state), pending = true)
                Div(attrs = { classes("skel", "usr-skel") })
            }

            is UserDetailUiState.Error -> {
                PageHeader(title = userCrumb(state))
                EmptyState(title = "This member can't be shown", body = state.error.message)
            }

            is UserDetailUiState.Ready -> {
                val user = state.user
                Div(attrs = { classes("usr-head") }) {
                    UserAvatar(userId = user.id, name = user.displayableName, size = AVATAR_SIZE)
                    PageHeader(title = user.displayableName, subtitle = user.email)
                }
                Div(attrs = { classes("usr-cols") }) {
                    PermissionsPanel(state = permissions, actions = actions)
                    Div(attrs = { classes("usr-details") }) {
                        Panel(title = "Details") {
                            MetaList(
                                listOf(
                                    MetaEntry("Status", user.status.lowercase().replaceFirstChar { it.uppercase() }),
                                    MetaEntry("Email", user.email),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The trail's last crumb: the member's name once it is known, and the page's own name until then. */
internal fun userCrumb(state: UserDetailUiState): String =
    if (state is UserDetailUiState.Ready) state.user.displayableName else "Member"

private const val AVATAR_SIZE = 56
