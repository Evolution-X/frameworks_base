/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.appdatabackup

import android.app.Application
import android.app.appbackup.AppBackupInfo
import android.app.appbackup.AppDataBackupRestoreManager
import android.app.appbackup.BackupRecord
import android.app.appbackup.BackupResult
import android.app.appbackup.IBackupProgressCallback
import android.app.appbackup.IRestoreProgressCallback
import android.content.Context
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val apps: List<AppBackupInfo> = emptyList(),
    val backups: List<BackupRecord> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loadingApps: Boolean = true,
    val loadingBackups: Boolean = true,
    val appsError: Boolean = false,
    val backupsError: Boolean = false,
    val operation: BackupOperation? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = operation != null
    val selectedBytes: Long get() = apps.filter { it.packageName in selected }
        .sumOf { it.dataSize.coerceAtLeast(0) }
}

data class BackupOperation(
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val cancellable: Boolean = false,
    val cancelling: Boolean = false,
)

data class BackupOptions(
    val components: Int,
    val excludeCache: Boolean,
    val passphrase: String?,
    val keepVersions: Int,
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val manager =
        application.getSystemService(Context.APP_DATA_BACKUP_SERVICE) as AppDataBackupRestoreManager
    private val backupDir = File("/data/media/${UserHandle.myUserId()}/AppDataBackup")
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()
    private var generation = 0
    private var operationToken: String? = null
    private var operationReady = false
    private var cancellationSent = false

    init {
        loadApps()
        loadBackups()
    }

    fun refresh() {
        if (state.value.busy) return
        if (!state.value.loadingApps) loadApps()
        if (!state.value.loadingBackups) loadBackups()
    }

    private fun loadApps() {
        mutableState.update { it.copy(loadingApps = true, appsError = false) }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val collator = Collator.getInstance()
                    manager.installedApps.sortedWith { a, b -> collator.compare(a.label, b.label) }
                }
                val packages = apps.map { it.packageName }.toSet()
                mutableState.update {
                    it.copy(apps = apps, loadingApps = false, selected = it.selected intersect packages)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to load apps", e)
                mutableState.update { it.copy(loadingApps = false, appsError = true) }
            }
        }
    }

    private fun loadBackups() {
        mutableState.update { it.copy(loadingBackups = true, backupsError = false) }
        viewModelScope.launch {
            try {
                val backups = withContext(Dispatchers.IO) {
                    manager.getAvailableBackups(backupDir.absolutePath)
                        .sortedByDescending { it.timestampMs }
                }
                mutableState.update { it.copy(backups = backups, loadingBackups = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to load backups", e)
                mutableState.update { it.copy(loadingBackups = false, backupsError = true) }
            }
        }
    }

    fun togglePackage(packageName: String) {
        if (state.value.busy) return
        mutableState.update {
            it.copy(selected = if (packageName in it.selected) it.selected - packageName
                else it.selected + packageName)
        }
    }

    fun selectPackages(packages: List<String>) {
        if (!state.value.busy) mutableState.update { it.copy(selected = it.selected + packages) }
    }

    fun clearSelection() {
        if (!state.value.busy) mutableState.update { it.copy(selected = emptySet()) }
    }

    fun consumeMessage(message: String) {
        mutableState.update { if (it.message == message) it.copy(message = null) else it }
    }

    fun backup(options: BackupOptions) {
        if (state.value.busy || state.value.loadingApps || state.value.appsError ||
            state.value.selected.isEmpty() || options.components == 0 || options.keepVersions < 0) return
        val apps = state.value.apps.filter { it.packageName in state.value.selected }
        val id = begin(R.string.progress_backup_title, R.string.progress_preparing, cancellable = true)
        val callback = object : IBackupProgressCallback.Stub() {
            override fun onBackupStarted(token: String, total: Int) = operationStarted(id, token) {
                it.copy(total = total, progress = 0f)
            }

            override fun onPackageBackupStarted(token: String, pkg: String, idx: Int, total: Int) =
                updateOperation(id) {
                    it.copy(detail = apps.find { app -> app.packageName == pkg }?.label ?: pkg,
                        total = total)
                }

            override fun onPackageBackupFinished(token: String, pkg: String, result: BackupResult) =
                updateOperation(id) {
                    val done = (it.completed + 1).coerceAtMost(it.total)
                    it.copy(completed = done, progress = done.toFloat() / it.total.coerceAtLeast(1))
                }

            override fun onBackupFinished(token: String, result: BackupResult) {
                complete(id, result, R.string.backup_complete, reloadBackups = true)
            }

            override fun onBackupCancelled(token: String) {
                complete(id, null, R.string.backup_cancelled, reloadBackups = true)
            }
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    manager.backupPackages(apps.map { it.packageName }, backupDir.absolutePath,
                        options.excludeCache, callback, options.passphrase, options.components,
                        options.keepVersions)
                }
                acceptToken(id, token)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun restore(record: BackupRecord, passphrase: String?) {
        if (state.value.busy) return
        val id = begin(R.string.progress_restore_title, R.string.progress_preparing, cancellable = true)
        val callback = object : IRestoreProgressCallback.Stub() {
            private var packageResult: BackupResult? = null

            override fun onRestoreStarted(token: String, total: Int) = operationStarted(id, token) {
                it.copy(total = total)
            }

            override fun onPackageRestoreStarted(token: String, pkg: String, idx: Int, total: Int) =
                updateOperation(id) {
                    it.copy(detail = text(R.string.progress_installing, record.label))
                }

            override fun onPackageDataRestoring(token: String, pkg: String) = updateOperation(id) {
                it.copy(detail = text(R.string.progress_restoring_data, record.label))
            }

            override fun onPackageRestoreFinished(token: String, pkg: String, result: BackupResult) {
                packageResult = result
                updateOperation(id) { it.copy(completed = it.completed + 1) }
            }

            override fun onRestoreFinished(token: String, result: BackupResult) {
                complete(id, packageResult?.takeUnless { it.isSuccess } ?: result,
                    R.string.restore_complete, reloadApps = true)
            }

            override fun onRestoreCancelled(token: String) {
                complete(id, null, R.string.restore_cancelled, reloadApps = true)
            }
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    manager.restorePackages(listOf(record.id), record.backupDir, callback,
                        passphrase, record.components)
                }
                acceptToken(id, token)
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun verify(record: BackupRecord, passphrase: String?) {
        if (state.value.busy) return
        val id = begin(R.string.progress_verify_title, R.string.progress_preparing)
        updateOperation(id) { it.copy(detail = record.label) }
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) {
                    manager.verifyBackup(record.id, record.backupDir, passphrase)
                }
                finish(id, error?.let { text(R.string.verify_failed, it) } ?: text(R.string.verify_valid))
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun delete(record: BackupRecord) {
        if (state.value.busy) return
        val id = begin(R.string.progress_delete_title, R.string.progress_preparing)
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    manager.deleteBackup(record.id, record.backupDir)
                }
                finish(id, text(if (deleted) R.string.delete_complete else R.string.delete_failed))
                loadBackups()
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun cancelOperation() {
        val operation = state.value.operation ?: return
        if (!operation.cancellable || operation.cancelling) return
        mutableState.update { it.copy(operation = operation.copy(cancelling = true)) }
        operationToken?.let { sendCancel(generation, it) }
    }

    private fun sendCancel(id: Int, token: String) {
        if (id != generation || !operationReady || cancellationSent) return
        cancellationSent = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.cancelOperation(token) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unable to cancel operation", e)
                if (id == generation && state.value.busy) {
                    cancellationSent = false
                    mutableState.update { it.copy(operation = it.operation?.copy(cancelling = false),
                        message = text(R.string.cancel_failed)) }
                }
            }
        }
    }

    private fun begin(title: Int, detail: Int, cancellable: Boolean = false): Int {
        generation++
        operationToken = null
        operationReady = false
        cancellationSent = false
        mutableState.update { it.copy(operation = BackupOperation(text(title), text(detail),
            cancellable = cancellable)) }
        return generation
    }

    private fun acceptToken(id: Int, token: String) {
        if (id != generation || !state.value.busy) return
        if (token.isBlank()) {
            finish(id, text(R.string.operation_failed))
        } else {
            operationToken = token
            if (state.value.operation?.cancelling == true) sendCancel(id, token)
        }
    }

    private fun updateOperation(id: Int, update: (BackupOperation) -> BackupOperation) {
        viewModelScope.launch {
            if (id == generation) mutableState.update { it.copy(operation = it.operation?.let(update)) }
        }
    }

    private fun operationStarted(id: Int, token: String, update: (BackupOperation) -> BackupOperation) {
        viewModelScope.launch {
            if (id != generation || !state.value.busy) return@launch
            operationToken = token
            operationReady = true
            mutableState.update { it.copy(operation = it.operation?.let(update)) }
            if (state.value.operation?.cancelling == true) sendCancel(id, token)
        }
    }

    private fun complete(id: Int, result: BackupResult?, success: Int,
        reloadBackups: Boolean = false, reloadApps: Boolean = false) {
        viewModelScope.launch {
            if (id != generation || !state.value.busy) return@launch
            val message = when {
                result == null || result.isSuccess -> text(success)
                state.value.operation?.cancelling == true || result.errorCode == BackupResult.ERROR_CANCELLED ->
                    text(R.string.operation_cancelled)
                result.status == BackupResult.STATUS_PARTIAL ->
                    text(R.string.operation_partial, result.message ?: text(R.string.operation_failed))
                else -> text(R.string.operation_error, result.message ?: text(R.string.operation_failed))
            }
            finish(id, message)
            if (reloadBackups) loadBackups()
            if (reloadApps) loadApps()
        }
    }

    private fun finish(id: Int, message: String) {
        if (id != generation || !state.value.busy) return
        operationToken = null
        operationReady = false
        mutableState.update { it.copy(operation = null, message = message) }
    }

    private fun fail(id: Int, e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Backup operation failed", e)
        finish(id, text(R.string.operation_failed))
    }

    private fun text(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    companion object {
        private const val TAG = "AppDataBackupUI"
    }
}
