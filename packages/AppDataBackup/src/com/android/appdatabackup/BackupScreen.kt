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

import android.app.appbackup.BackupRecord
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class BackupAction { Restore, Verify, Delete }

@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var backupQuery by rememberSaveable { mutableStateOf("") }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var action by rememberSaveable { mutableStateOf<BackupAction?>(null) }
    var record by rememberSaveable { mutableStateOf<BackupRecord?>(null) }
    val appList = rememberLazyListState()
    val backupList = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val motion = MaterialTheme.motionScheme
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val apps = remember(state.apps, appQuery) {
        val query = appQuery.trim()
        state.apps.filter { query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    val backups = remember(state.backups, backupQuery) {
        val query = backupQuery.trim()
        state.backups.filter { query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    val selectedBytes = remember(state.apps, state.selected) { state.selectedBytes }
    val archivedBytes = remember(state.backups) { state.backups.sumOf { it.totalSize.coerceAtLeast(0) } }
    val showTitle by remember {
        derivedStateOf { (if (tab == 0) appList else backupList).firstVisibleItemIndex > 0 }
    }
    val currentRecord = record
    val chooseAction: (BackupRecord, BackupAction) -> Unit = { selected, selectedAction ->
        record = selected
        action = selectedAction
    }
    BackHandler(tab != 0) { tab = 0 }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage(message)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(showTitle, enter = fadeIn(motion.fastEffectsSpec()),
                        exit = fadeOut(motion.fastEffectsSpec())) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (tab != 0) tab = 0 else onBack() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy &&
                        !state.loadingApps && !state.loadingBackups, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
                Column(Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedVisibility(tab == 0 && state.selected.isNotEmpty() && !state.busy,
                        enter = fadeIn(motion.fastEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec())) {
                        Button(onClick = { showOptions = true }, enabled = !state.loadingApps && !state.appsError,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                            Icon(Icons.Rounded.Backup, null)
                            Spacer(Modifier.width(12.dp))
                            Text(pluralStringResource(R.plurals.backup_action_count, state.selected.size, state.selected.size),
                                style = MaterialTheme.typography.titleMediumEmphasized)
                        }
                    }
                    BackupNavigation(tab, onSelect = { tab = it })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()
            .padding(start = padding.calculateStartPadding(layoutDirection), top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection))
            .consumeWindowInsets(padding)) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter)) {
                AnimatedContent(state.operation, contentKey = { it != null },
                    modifier = Modifier.fillMaxWidth(), transitionSpec = {
                        fadeIn(motion.fastEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(sizeAnimationSpec = { _, _ -> motion.defaultSpatialSpec() }))
                    }, label = "operation visibility") { operation ->
                    if (operation != null) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OperationCard(operation, viewModel::cancelOperation)
                    }
                }
                AnimatedContent(tab, modifier = Modifier.weight(1f), transitionSpec = {
                    val forward = (targetState > initialState) != isRtl
                    (fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) {
                        if (forward) it / 8 else -it / 8
                    }).togetherWith(fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec()) {
                        if (forward) -it / 8 else it / 8
                    }).using(SizeTransform(clip = false))
                }, label = "backup page") { page ->
                    val library = page == 1
                    val loading = if (library) state.loadingBackups else state.loadingApps
                    val error = if (library) state.backupsError else state.appsError
                    val query = if (library) backupQuery else appQuery
                    val empty = if (library) backups.isEmpty() else apps.isEmpty()
                    val allEmpty = if (library) state.backups.isEmpty() else state.apps.isEmpty()
                    LazyColumn(state = if (library) backupList else appList, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item(key = "heading") {
                            BackupHeading(stringResource(if (library) R.string.library_headline else R.string.backup_headline),
                                stringResource(if (library) R.string.library_subheadline else R.string.backup_subheadline))
                        }
                        item(key = "summary") {
                            Box(Modifier.padding(vertical = 16.dp)) {
                                BackupSummary(if (library) state.backups.size else state.selected.size,
                                    if (library) archivedBytes else selectedBytes, library)
                            }
                        }
                        if (!allEmpty || query.isNotEmpty()) {
                            item(key = "search") {
                                OutlinedTextField(value = query, onValueChange = {
                                    if (library) backupQuery = it else appQuery = it
                                }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    singleLine = true, shape = CircleShape,
                                    label = { Text(stringResource(if (library) R.string.search_backups else R.string.search_apps)) },
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    trailingIcon = {
                                        if (query.isNotEmpty()) IconButton(onClick = {
                                            if (library) backupQuery = "" else appQuery = ""
                                        }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear_search)) }
                                    })
                            }
                        }
                        if (!empty && !loading && !error) {
                            item(key = "section") {
                                FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pluralStringResource(if (library) R.plurals.backup_count else R.plurals.app_count,
                                        if (library) backups.size else apps.size, if (library) backups.size else apps.size),
                                        Modifier.align(Alignment.CenterVertically).weight(1f).semantics { heading() },
                                        style = MaterialTheme.typography.titleMediumEmphasized)
                                    if (!library) {
                                        if (state.selected.isNotEmpty()) TextButton(onClick = viewModel::clearSelection,
                                            enabled = !state.busy) { Text(stringResource(R.string.action_clear)) }
                                        TextButton(onClick = { viewModel.selectPackages(apps.map { it.packageName }) },
                                            enabled = !state.busy && apps.any { it.packageName !in state.selected }) {
                                            Text(stringResource(if (appQuery.isBlank()) R.string.action_select_all
                                                else R.string.action_select_results))
                                        }
                                    }
                                }
                            }
                        }
                        when {
                            loading -> item(key = "loading") {
                                Column(Modifier.fillMaxWidth().padding(40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LoadingIndicator(Modifier.size(64.dp))
                                    Text(stringResource(if (library) R.string.loading_backups else R.string.loading_apps),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            error -> item(key = "error") {
                                BackupEmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.load_failed_title),
                                    stringResource(R.string.load_failed_message), stringResource(R.string.retry), viewModel::refresh)
                            }
                            empty -> item(key = "empty") {
                                when {
                                    query.isNotBlank() -> BackupEmptyState(Icons.Rounded.SearchOff,
                                        stringResource(R.string.no_results_title), stringResource(R.string.no_results_message),
                                        stringResource(R.string.clear_search), { if (library) backupQuery = "" else appQuery = "" })
                                    library -> BackupEmptyState(Icons.Rounded.Inventory2,
                                        stringResource(R.string.no_backups_title), stringResource(R.string.no_backups_message),
                                        stringResource(R.string.choose_apps), { tab = 0 })
                                    else -> BackupEmptyState(Icons.Rounded.Apps, stringResource(R.string.no_apps_title),
                                        stringResource(R.string.no_apps_message), stringResource(R.string.refresh), viewModel::refresh)
                                }
                            }
                            library -> itemsIndexed(backups, key = { _, entry -> entry.id }) { _, entry ->
                                SavedBackupCard(entry, !state.busy,
                                    onRestore = { chooseAction(entry, BackupAction.Restore) },
                                    onVerify = { chooseAction(entry, BackupAction.Verify) },
                                    onDelete = { chooseAction(entry, BackupAction.Delete) },
                                    modifier = Modifier.padding(bottom = 8.dp).animateItem(
                                        fadeInSpec = motion.defaultEffectsSpec(),
                                        placementSpec = motion.defaultSpatialSpec(),
                                        fadeOutSpec = motion.fastEffectsSpec()))
                            }
                            else -> itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                                AppBackupRow(app, app.packageName in state.selected, index, apps.size,
                                    enabled = !state.busy, onToggle = { viewModel.togglePackage(app.packageName) },
                                    modifier = Modifier.animateItem(fadeInSpec = motion.defaultEffectsSpec(),
                                        placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = motion.fastEffectsSpec()))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showOptions && !state.busy) {
        BackupOptionsSheet(state.selected.size, onDismiss = { showOptions = false }, onConfirm = {
            showOptions = false
            viewModel.backup(it)
        })
    }
    if (currentRecord != null && action != null && !state.busy) {
        BackupActionDialog(currentRecord, action!!, onDismiss = { action = null; record = null },
            onConfirm = { passphrase ->
                when (action) {
                    BackupAction.Restore -> viewModel.restore(currentRecord, passphrase)
                    BackupAction.Verify -> viewModel.verify(currentRecord, passphrase)
                    BackupAction.Delete -> viewModel.delete(currentRecord)
                    null -> Unit
                }
                action = null
                record = null
            })
    }
}
