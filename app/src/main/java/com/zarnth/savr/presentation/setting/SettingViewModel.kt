package com.zarnth.savr.presentation.setting

import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zarnth.savr.data.backup.BackupFileInfo
import com.zarnth.savr.data.backup.BackupManager
import com.zarnth.savr.data.backup.BackupResult
import com.zarnth.savr.data.backup.formatBackupTime
import com.zarnth.savr.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingViewModel(
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingState(
            themeMode = settingsRepository.getThemeMode(),
            tapAction = settingsRepository.getTapAction(),
            dynamicColor = settingsRepository.getDynamicColor(),
            isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            viewMode = settingsRepository.getViewMode(),
            autoBackupEnabled = settingsRepository.getAutoBackupEnabled(),
            hasBackupFolder = backupManager.hasBackupFolder(),
            backupRetention = settingsRepository.getBackupRetention(),
            quickSaveEnabled = settingsRepository.getQuickSaveEnabled()
        )
    )
    val state = _state.asStateFlow()

    init {
        refreshBackups()
        viewModelScope.launch {
            backupManager.lastBackupTimeMillis.collect { millis ->
                _state.update { it.copy(lastBackupTimeText = formatLastBackupTime(millis)) }
            }
        }
        viewModelScope.launch {
            backupManager.lastError.collect { error ->
                _state.update { it.copy(backupError = error) }
            }
        }
    }

    fun onEvent(event: SettingEvents) {
        when (event) {
            is SettingEvents.SelectTheme -> {
                settingsRepository.setThemeMode(event.themeMode)
                _state.update {
                    it.copy(
                        themeMode = event.themeMode,
                        showThemeSheet = false
                    )
                }
            }

            SettingEvents.ShowThemeSheet -> {
                _state.update { it.copy(showThemeSheet = true) }
            }

            SettingEvents.HideThemeSheet -> {
                _state.update { it.copy(showThemeSheet = false) }
            }

            is SettingEvents.SelectTapAction -> {
                settingsRepository.setTapAction(event.action)
                _state.update {
                    it.copy(
                        tapAction = event.action,
                        showTapActionSheet = false
                    )
                }
            }

            SettingEvents.ShowTapActionSheet -> {
                _state.update { it.copy(showTapActionSheet = true) }
            }

            SettingEvents.HideTapActionSheet -> {
                _state.update { it.copy(showTapActionSheet = false) }
            }

            SettingEvents.ExportData -> {
                exportData()
            }

            is SettingEvents.ToggleDynamicColor -> {
                settingsRepository.setDynamicColor(event.enabled)
                _state.update { it.copy(dynamicColor = event.enabled) }
            }

            is SettingEvents.ToggleViewMode -> {
                settingsRepository.setViewMode(event.viewMode)
                _state.update {
                    it.copy(
                        viewMode = event.viewMode,
                        showViewModeSheet = false
                    )
                }
            }

            SettingEvents.ShowViewModeSheet -> {
                _state.update { it.copy(showViewModeSheet = true) }
            }

            SettingEvents.HideViewModeSheet -> {
                _state.update { it.copy(showViewModeSheet = false) }
            }

            SettingEvents.DismissExport -> {
                _state.update { it.copy(exportState = ExportState.Idle) }
            }

            is SettingEvents.ImportData -> {
                importData(event.json)
            }

            SettingEvents.DismissImportResult -> {
                _state.update { it.copy(importState = ImportState.Idle) }
            }

            is SettingEvents.ToggleAutoBackup -> {
                if (event.enabled) {
                    _state.update { it.copy(showAutoBackupInfoDialog = true) }
                } else {
                    backupManager.disableAutoBackup()
                    _state.update { it.copy(autoBackupEnabled = false) }
                }
            }

            SettingEvents.ConfirmAutoBackupEnable -> {
                backupManager.enableAutoBackup()
                _state.update { it.copy(autoBackupEnabled = true, showAutoBackupInfoDialog = false) }
                runBackup(showResult = false)
            }

            is SettingEvents.BackupFolderSelected -> onBackupFolderSelected(event)

            SettingEvents.BackupNow -> runBackup(showResult = true)

            SettingEvents.DismissBackupMessage -> {
                _state.update { it.copy(backupMessage = null) }
            }

            SettingEvents.RefreshBackups -> refreshBackups()

            SettingEvents.ShowRetentionSheet -> {
                _state.update { it.copy(showRetentionSheet = true) }
            }

            SettingEvents.HideRetentionSheet -> {
                _state.update { it.copy(showRetentionSheet = false) }
            }

            is SettingEvents.SelectRetention -> {
                settingsRepository.setBackupRetention(event.count)
                _state.update { it.copy(backupRetention = event.count, showRetentionSheet = false) }
            }

            SettingEvents.RestoreLatest -> {
                viewModelScope.launch {
                    refreshBackupsNow()
                    val latest = _state.value.backups.firstOrNull { !it.isSafetyCopy }
                    if (latest == null) {
                        _state.update { it.copy(backupMessage = noBackupsMessage()) }
                    } else {
                        _state.update { it.copy(pendingRestore = latest) }
                    }
                }
            }

            SettingEvents.ShowRestoreList -> {
                viewModelScope.launch {
                    refreshBackupsNow()
                    if (_state.value.backups.isEmpty()) {
                        _state.update { it.copy(backupMessage = noBackupsMessage()) }
                    } else {
                        _state.update { it.copy(showRestoreList = true) }
                    }
                }
            }

            SettingEvents.HideRestoreList -> {
                _state.update { it.copy(showRestoreList = false) }
            }

            is SettingEvents.RequestRestore -> {
                _state.update { it.copy(pendingRestore = event.backup, showRestoreList = false) }
            }

            SettingEvents.CancelRestore -> {
                _state.update { it.copy(pendingRestore = null) }
            }

            SettingEvents.ConfirmRestore -> {
                val backup = _state.value.pendingRestore
                if (backup != null) restore(backup)
            }

            SettingEvents.DismissAutoBackupInfoDialog -> {
                _state.update { it.copy(showAutoBackupInfoDialog = false) }
            }

            is SettingEvents.ToggleQuickSave -> {
                settingsRepository.setQuickSaveEnabled(event.enabled)
                _state.update { it.copy(quickSaveEnabled = event.enabled) }
            }

            is SettingEvents.ImportBrowserBookmarks -> {
                importBrowserBookmarks(event.html)
            }

            SettingEvents.DismissBrowserImportResult -> {
                _state.update { it.copy(browserImportState = BrowserImportState.Idle) }
            }

            SettingEvents.ExportBrowserBookmarks -> {
                exportBrowserData()
            }

            SettingEvents.DismissBrowserExport -> {
                _state.update { it.copy(browserExportState = ExportState.Idle) }
            }

            SettingEvents.ShowExportSheet -> {
                _state.update { it.copy(showExportSheet = true) }
            }

            SettingEvents.HideExportSheet -> {
                _state.update { it.copy(showExportSheet = false) }
            }

            SettingEvents.ShowImportSheet -> {
                _state.update { it.copy(showImportSheet = true) }
            }

            SettingEvents.HideImportSheet -> {
                _state.update { it.copy(showImportSheet = false) }
            }

            SettingEvents.TestCrash -> {
                triggerTestCrash()
            }
        }
    }

    private fun triggerTestCrash() {
        Handler(Looper.getMainLooper()).post {
            throw RuntimeException("Savr test crash triggered from Settings")
        }
    }

    private fun exportData() {
        viewModelScope.launch {
            _state.update { it.copy(exportState = ExportState.Loading) }
            try {
                val jsonString = backupManager.generateBackupJson()
                _state.update { it.copy(exportState = ExportState.Ready(jsonString)) }
            } catch (e: Exception) {
                _state.update { it.copy(exportState = ExportState.Error(e.message ?: "Export failed")) }
            }
        }
    }

    private fun importData(jsonString: String) {
        viewModelScope.launch {
            _state.update { it.copy(importState = ImportState.Loading) }
            try {
                backupManager.importFromJson(jsonString)
                backupManager.fetchMissingImages()
                _state.update { it.copy(importState = ImportState.Success) }
            } catch (e: Exception) {
                _state.update { it.copy(importState = ImportState.Error(e.message ?: "Import failed")) }
            }
        }
    }

    private fun exportBrowserData() {
        viewModelScope.launch {
            _state.update { it.copy(browserExportState = ExportState.Loading) }
            try {
                val html = backupManager.generateExportHtml()
                _state.update { it.copy(browserExportState = ExportState.Ready(html)) }
            } catch (e: Exception) {
                _state.update { it.copy(browserExportState = ExportState.Error(e.message ?: "Export failed")) }
            }
        }
    }

    private fun importBrowserBookmarks(html: String) {
        viewModelScope.launch {
            _state.update { it.copy(browserImportState = BrowserImportState.Loading) }
            try {
                val result = backupManager.importFromBrowserBookmarks(html)
                backupManager.fetchMissingImages()
                _state.update { it.copy(browserImportState = BrowserImportState.Success(result.imported, result.skipped, result.collections)) }
            } catch (e: Exception) {
                _state.update { it.copy(browserImportState = BrowserImportState.Error(e.message ?: "Import failed")) }
            }
        }
    }

    private fun formatLastBackupTime(millis: Long): String {
        if (millis <= 0L) return ""
        return "Up to date as of ${formatBackupTime(millis)}"
    }

    private fun noBackupsMessage(): String =
        if (_state.value.hasBackupFolder) "No backups found in the backup folder yet."
        else "Choose a backup folder first."

    private fun onBackupFolderSelected(event: SettingEvents.BackupFolderSelected) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { backupManager.setBackupFolder(event.uri) }
                _state.update { it.copy(hasBackupFolder = true) }
                if (event.enableAfter) {
                    backupManager.enableAutoBackup()
                    _state.update { it.copy(autoBackupEnabled = true) }
                }
                if (event.enableAfter || _state.value.autoBackupEnabled) {
                    runBackup(showResult = false)
                } else {
                    refreshBackupsNow()
                }
            } catch (e: Exception) {
                _state.update { it.copy(backupMessage = "Could not use this folder: ${e.message}") }
            }
        }
    }

    private fun runBackup(showResult: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true) }
            val result = backupManager.backupNow()
            _state.update {
                it.copy(isBusy = false, backupMessage = if (showResult) describe(result) else it.backupMessage)
            }
            refreshBackupsNow()
        }
    }

    private fun describe(result: BackupResult): String = when (result) {
        is BackupResult.Created -> "Backup saved as ${result.fileName}."
        BackupResult.Unchanged -> "Already up to date. Nothing changed since the last backup."
        BackupResult.NothingToBackUp -> "There is nothing to back up yet."
        is BackupResult.Failed -> result.message
    }

    private fun restore(backup: BackupFileInfo) {
        viewModelScope.launch {
            _state.update { it.copy(pendingRestore = null, isBusy = true) }
            val message = try {
                val safetyCopySaved = backupManager.restoreFromBackup(backup.uri)
                "Restored the backup from ${formatBackupTime(backup.timeMillis)}." +
                    if (safetyCopySaved) " Your previous data was saved as a safety copy in the backup folder." else ""
            } catch (e: Exception) {
                "Restore failed: ${e.message ?: "unknown error"}. Your current data was not changed."
            }
            _state.update { it.copy(isBusy = false, backupMessage = message) }
            refreshBackupsNow()
        }
    }

    private fun refreshBackups() {
        viewModelScope.launch { refreshBackupsNow() }
    }

    private suspend fun refreshBackupsNow() {
        val (list, folderName, hasFolder) = withContext(Dispatchers.IO) {
            Triple(backupManager.listBackups(), backupManager.getBackupFolderName(), backupManager.hasBackupFolder())
        }
        _state.update { it.copy(backups = list, backupFolderName = folderName, hasBackupFolder = hasFolder) }
    }
}
