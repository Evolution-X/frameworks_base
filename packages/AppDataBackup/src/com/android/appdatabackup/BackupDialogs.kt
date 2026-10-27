/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.android.appdatabackup

import android.app.appbackup.AppDataBackupRestoreManager
import android.app.appbackup.BackupRecord
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun BackupOptionsSheet(count: Int, onDismiss: () -> Unit, onConfirm: (BackupOptions) -> Unit) {
    var components by rememberSaveable { mutableIntStateOf(AppDataBackupRestoreManager.COMPONENT_APK or
        AppDataBackupRestoreManager.COMPONENT_CE_DATA or AppDataBackupRestoreManager.COMPONENT_DE_DATA or
        AppDataBackupRestoreManager.COMPONENT_EXTERNAL) }
    var excludeCache by rememberSaveable { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var keepText by rememberSaveable { mutableStateOf("") }
    val keep = if (keepText.isBlank()) 0 else keepText.toIntOrNull()
    val validRetention = keep != null && keep >= 0
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BackupEmblem(Icons.Rounded.Backup, Modifier.size(56.dp))
                Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.backup_options_title),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        modifier = Modifier.semantics { heading() })
                    Text(pluralStringResource(R.plurals.selected_count, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogSectionTitle(stringResource(R.string.section_include))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppDataBackupRestoreManager.COMPONENT_APK to R.string.comp_apk,
                        AppDataBackupRestoreManager.COMPONENT_CE_DATA to R.string.comp_app_data,
                        AppDataBackupRestoreManager.COMPONENT_DE_DATA to R.string.comp_de_data,
                        AppDataBackupRestoreManager.COMPONENT_EXTERNAL to R.string.comp_external,
                    ).forEach { (flag, label) ->
                        ComponentChip(components and flag != 0, stringResource(label)) { components = components xor flag }
                    }
                }
                Text(stringResource(if (components == 0) R.string.select_one_component else R.string.components_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (components == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().toggleable(value = excludeCache, role = Role.Switch,
                    onValueChange = { excludeCache = it }).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.chip_exclude_cache), style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(stringResource(R.string.exclude_cache_helper), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = excludeCache, onCheckedChange = null,
                        thumbContent = if (excludeCache) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogSectionTitle(stringResource(R.string.section_encryption))
                PassphraseField(passphrase, onValueChange = { passphrase = it }, optional = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogSectionTitle(stringResource(R.string.section_retention))
                OutlinedTextField(value = keepText, onValueChange = { if (it.all(Char::isDigit)) keepText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.retention_label)) },
                    leadingIcon = { Icon(Icons.Rounded.History, null) }, isError = !validRetention,
                    supportingText = { Text(stringResource(if (validRetention) R.string.retention_helper else R.string.retention_invalid)) })
            }
            Text(stringResource(R.string.backup_location), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onConfirm(BackupOptions(components, excludeCache,
                passphrase.ifEmpty { null }, keep ?: 0)) },
                enabled = count > 0 && components != 0 && validRetention,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                Icon(Icons.Rounded.Backup, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.action_back_up), style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
    }
}

@Composable
private fun DialogSectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMediumEmphasized)
}

@Composable
private fun ComponentChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 8.dp else if (selected) 20.dp else 12.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "component shape")
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        modifier = Modifier.backupPress(interaction), shape = RoundedCornerShape(corner),
        interactionSource = interaction,
        leadingIcon = if (selected) { { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) } } else null)
}

@Composable
private fun PassphraseField(value: String, onValueChange: (String) -> Unit, optional: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
        singleLine = true, shape = RoundedCornerShape(20.dp),
        label = { Text(stringResource(if (optional) R.string.passphrase_optional else R.string.passphrase_label)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        leadingIcon = { Icon(Icons.Rounded.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    stringResource(if (visible) R.string.hide_passphrase else R.string.show_passphrase))
            }
        }, supportingText = if (optional) { { Text(stringResource(R.string.encryption_helper)) } } else null)
}

@Composable
fun BackupActionDialog(record: BackupRecord, action: BackupAction, onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit) {
    var passphrase by remember(record.id, action) { mutableStateOf("") }
    val needsPassphrase = record.isEncrypted && action != BackupAction.Delete
    val title = when (action) {
        BackupAction.Restore -> stringResource(R.string.confirm_restore_title, record.label)
        BackupAction.Verify -> stringResource(R.string.confirm_verify_title, record.label)
        BackupAction.Delete -> stringResource(R.string.confirm_delete_title)
    }
    val actionLabel = stringResource(when (action) {
        BackupAction.Restore -> R.string.restore
        BackupAction.Verify -> R.string.verify
        BackupAction.Delete -> R.string.delete
    })
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(32.dp),
        icon = { BackupEmblem(when (action) {
            BackupAction.Restore -> Icons.Rounded.Restore
            BackupAction.Verify -> Icons.Rounded.VerifiedUser
            BackupAction.Delete -> Icons.Rounded.DeleteOutline
        }, Modifier.size(64.dp)) },
        title = { Text(title, style = MaterialTheme.typography.headlineSmallEmphasized) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(when (action) {
                    BackupAction.Restore -> stringResource(R.string.confirm_restore_message)
                    BackupAction.Verify -> stringResource(R.string.confirm_verify_message)
                    BackupAction.Delete -> stringResource(R.string.confirm_delete_message, record.label)
                })
                if (needsPassphrase) {
                    Text(stringResource(R.string.encrypted_backup_message), style = MaterialTheme.typography.bodyMedium)
                    PassphraseField(passphrase, onValueChange = { passphrase = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase.ifEmpty { null }) },
                enabled = !needsPassphrase || passphrase.isNotEmpty(), shapes = ButtonDefaults.shapes(),
                colors = if (action == BackupAction.Delete) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                    else ButtonDefaults.buttonColors()) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
