/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.appdatabackup

import android.app.appbackup.AppDataBackupRestoreManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal val systemDataTypes = listOf(
    AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS,
    AppDataBackupRestoreManager.DATA_TYPE_MESSAGES,
    AppDataBackupRestoreManager.DATA_TYPE_WIFI,
)

internal fun systemDataLabel(type: Int): Int = when (type) {
    AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS -> R.string.system_calls
    AppDataBackupRestoreManager.DATA_TYPE_MESSAGES -> R.string.system_messages
    else -> R.string.system_wifi
}

internal fun systemDataHelp(type: Int): Int = when (type) {
    AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS -> R.string.system_calls_help
    AppDataBackupRestoreManager.DATA_TYPE_MESSAGES -> R.string.system_messages_help
    else -> R.string.system_wifi_help
}

internal fun systemDataIcon(type: Int) = when (type) {
    AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS -> Icons.Rounded.Call
    AppDataBackupRestoreManager.DATA_TYPE_MESSAGES -> Icons.AutoMirrored.Rounded.Message
    else -> Icons.Rounded.Wifi
}

@Composable
internal fun SystemDataCards(enabled: Boolean, onBackup: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.system_data), style = MaterialTheme.typography.titleMediumEmphasized)
        systemDataTypes.forEach { type ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BackupEmblem(systemDataIcon(type), Modifier.size(48.dp))
                    Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(systemDataLabel(type)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(systemDataHelp(type)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onBackup(type) }, enabled = enabled) {
                        Text(stringResource(R.string.action_back_up))
                    }
                }
            }
        }
    }
}
