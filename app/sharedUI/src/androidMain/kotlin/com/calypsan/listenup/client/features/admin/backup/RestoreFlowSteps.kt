package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.FlowStep
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_restore_step_choose
import listenup.composeapp.generated.resources.admin_restore_step_choose_sub
import listenup.composeapp.generated.resources.admin_restore_step_restore
import listenup.composeapp.generated.resources.admin_restore_step_restore_sub
import listenup.composeapp.generated.resources.admin_restore_step_review
import listenup.composeapp.generated.resources.admin_restore_step_review_sub
import listenup.composeapp.generated.resources.admin_restore_step_sign_in
import listenup.composeapp.generated.resources.admin_restore_step_sign_in_sub
import org.jetbrains.compose.resources.stringResource

/** Where each restore screen sits in [restoreFlowSteps]. */
internal object RestoreFlowStep {
    /** Picking the backup — the restore-from-file screen, including its upload. */
    const val CHOOSE = 0

    /** Reviewing the backup and confirming the restore. */
    const val REVIEW = 1

    /** The server swapping the backup in. */
    const val RESTORE = 2

    /** Done — the admin signs in again with an account from the backup. */
    const val SIGN_IN = 3
}

/**
 * The restore flow's steps, shared by the restore-from-file and restore screens so a tablet shows
 * one journey across both: choosing a backup, reviewing it, the restore, and signing in again.
 */
@Composable
internal fun restoreFlowSteps(): List<FlowStep> =
    listOf(
        FlowStep(
            icon = Icons.Outlined.Inventory2,
            title = stringResource(Res.string.admin_restore_step_choose),
            subtitle = stringResource(Res.string.admin_restore_step_choose_sub),
        ),
        FlowStep(
            icon = Icons.Outlined.Visibility,
            title = stringResource(Res.string.admin_restore_step_review),
            subtitle = stringResource(Res.string.admin_restore_step_review_sub),
        ),
        FlowStep(
            icon = Icons.Outlined.Restore,
            title = stringResource(Res.string.admin_restore_step_restore),
            subtitle = stringResource(Res.string.admin_restore_step_restore_sub),
        ),
        FlowStep(
            icon = Icons.AutoMirrored.Outlined.Login,
            title = stringResource(Res.string.admin_restore_step_sign_in),
            subtitle = stringResource(Res.string.admin_restore_step_sign_in_sub),
        ),
    )
