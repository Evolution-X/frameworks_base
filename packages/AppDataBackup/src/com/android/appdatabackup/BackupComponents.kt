/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.android.appdatabackup

import android.app.appbackup.AppBackupInfo
import android.app.appbackup.AppDataBackupRestoreManager
import android.app.appbackup.BackupRecord
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Modifier.backupPress(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press scale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun BackupEmblem(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(modifier.clip(MaterialShapes.Cookie9Sided.toShape()).background(containerColor),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.fillMaxSize(0.45f), tint = contentColor)
    }
}

@Composable
fun BackupHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp), Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmallEmphasized)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BackupSummary(count: Int, bytes: Long, library: Boolean) {
    val motion = MaterialTheme.motionScheme
    val rotation by animateFloatAsState(if (count > 0) -12f else 0f,
        motion.defaultSpatialSpec(), label = "emblem rotation")
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BackupEmblem(if (library) Icons.Rounded.Inventory2 else Icons.Rounded.Backup,
                    Modifier.size(64.dp).graphicsLayer { rotationZ = rotation },
                    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(if (library) R.string.library_summary_label else R.string.selection_label),
                        style = MaterialTheme.typography.labelLarge)
                    AnimatedContent(count, transitionSpec = {
                        (fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { it / 3 })
                            .togetherWith(fadeOut(motion.fastEffectsSpec()) +
                                slideOutVertically(motion.fastSpatialSpec()) { -it / 3 })
                    }, label = "summary count") { value ->
                        Text(if (library) pluralStringResource(R.plurals.backup_count, value, value)
                            else if (value == 0) stringResource(R.string.summary_empty_count)
                            else pluralStringResource(R.plurals.selected_count, value, value),
                            style = MaterialTheme.typography.headlineSmallEmphasized)
                    }
                }
            }
            Text(when {
                library -> stringResource(R.string.library_summary_size, backupSize(bytes))
                count == 0 -> stringResource(R.string.summary_empty_size)
                else -> stringResource(R.string.summary_size, backupSize(bytes))
            }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun BackupNavigation(tab: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    Surface(
        modifier = modifier.widthIn(max = 440.dp).animateContentSize(motion.defaultSpatialSpec()),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 0.dp,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            listOf(R.string.tab_apps, R.string.tab_backups).forEachIndexed { index, title ->
                val label = stringResource(title)
                val selected = tab == index
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.94f else 1f,
                    motion.fastSpatialSpec(), label = "navigation press")
                val radius by animateDpAsState(if (pressed) 16.dp else 26.dp,
                    motion.fastSpatialSpec(), label = "navigation shape")
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    motion.fastEffectsSpec(), label = "navigation color")
                Row(Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(radius)).background(color)
                    .selectable(selected = selected, role = Role.Tab,
                        interactionSource = interaction, indication = LocalIndication.current, onClick = {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            }
                        }).semantics { contentDescription = label }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalContentColor provides
                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant) {
                        Icon(if (index == 0) Icons.Rounded.Apps else Icons.Rounded.Inventory2,
                            null, Modifier.size(22.dp))
                        AnimatedVisibility(selected,
                            enter = fadeIn() + expandHorizontally(motion.defaultSpatialSpec()),
                            exit = fadeOut() + shrinkHorizontally()) {
                            Text(label, Modifier.padding(start = 6.dp).clearAndSetSemantics {},
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLargeEmphasized)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppBackupRow(app: AppBackupInfo, selected: Boolean, index: Int, count: Int,
    enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val innerCorner by animateDpAsState(if (pressed) 16.dp else if (selected) 12.dp else 4.dp,
        motion.fastSpatialSpec(), label = "selection corner")
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        motion.fastEffectsSpec(), label = "selection surface")
    val foreground by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        motion.fastEffectsSpec(), label = "selection text")
    val shape = RoundedCornerShape(
        topStart = if (index == 0) 28.dp else innerCorner,
        topEnd = if (index == 0) 28.dp else innerCorner,
        bottomStart = if (index == count - 1) 28.dp else innerCorner,
        bottomEnd = if (index == count - 1) 28.dp else innerCorner)
    Row(modifier.fillMaxWidth().backupPress(interaction).clip(shape).background(background)
        .toggleable(value = selected, enabled = enabled, role = Role.Checkbox,
            interactionSource = interaction, indication = ripple(), onValueChange = {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onToggle()
            }).padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppAvatar(app.packageName, app.label)
        Column(Modifier.weight(1f), Arrangement.spacedBy(3.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMediumEmphasized, color = foreground)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                color = foreground.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.app_size_version, backupSize(app.dataSize), app.versionName ?: ""),
                style = MaterialTheme.typography.labelMedium, color = foreground)
        }
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SavedBackupCard(record: BackupRecord, enabled: Boolean, onRestore: () -> Unit,
    onVerify: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val date = remember(record.timestampMs, context.resources.configuration) {
        val value = Date(record.timestampMs)
        "${DateFormat.getMediumDateFormat(context).format(value)} · ${DateFormat.getTimeFormat(context).format(value)}"
    }
    var expanded by remember(record.id) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(20.dp),
            Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppAvatar(record.packageName, record.label)
                Column(Modifier.weight(1f), Arrangement.spacedBy(3.dp)) {
                    Text(record.label, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(record.packageName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_named_backup, record.label))
                }
            }
            Text(date, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetadataPill(backupSize(record.totalSize))
                MetadataPill(stringResource(R.string.backup_version, record.versionName ?: ""))
                if (record.isEncrypted) MetadataPill(stringResource(R.string.content_encrypted), Icons.Rounded.Lock)
                if (record.state != BackupRecord.STATE_OK) MetadataPill(
                    stringResource(R.string.backup_incomplete), Icons.Rounded.Info)
            }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(if (expanded) R.string.hide_contents else R.string.show_contents))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (expanded) {
                Text(backupContents(record), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRestore, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.restore))
                }
                TextButton(onClick = onVerify, enabled = enabled) {
                    Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.verify))
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String, icon: ImageVector? = null) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AppAvatar(packageName: String, label: String) {
    val context = LocalContext.current
    val size = with(LocalDensity.current) { 48.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, packageName, size) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, null, Modifier.fillMaxSize())
        else Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
fun OperationCard(operation: BackupOperation, onCancel: () -> Unit) {
    val progress by animateFloatAsState(operation.progress ?: 0f,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "operation progress")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(40.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                    Text(if (operation.cancelling) stringResource(R.string.progress_cancelling) else operation.title,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(operation.detail, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (operation.progress != null) {
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f))
                Text(stringResource(R.string.progress_app_count, operation.completed, operation.total),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (operation.cancellable) {
                TextButton(onClick = onCancel, enabled = !operation.cancelling,
                    modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
fun BackupEmptyState(icon: ImageVector, title: String, body: String,
    action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackupEmblem(icon, Modifier.size(88.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
    }
}

@Composable
fun backupSize(bytes: Long): String = if (bytes < 0) stringResource(R.string.size_unknown)
    else Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun backupContents(record: BackupRecord): String {
    val parts = listOf(
        AppDataBackupRestoreManager.COMPONENT_APK to R.string.content_apk,
        AppDataBackupRestoreManager.COMPONENT_CE_DATA to R.string.content_app_data,
        AppDataBackupRestoreManager.COMPONENT_DE_DATA to R.string.content_device_data,
        AppDataBackupRestoreManager.COMPONENT_EXTERNAL to R.string.content_external,
    ).filter { (flag, _) -> record.components and flag != 0 }.map { (_, text) -> stringResource(text) }
    return stringResource(R.string.content_includes,
        (parts + stringResource(R.string.content_permissions)).joinToString(" · "))
}
