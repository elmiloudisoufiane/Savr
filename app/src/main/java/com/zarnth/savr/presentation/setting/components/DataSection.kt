package com.zarnth.savr.presentation.setting.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zarnth.savr.R
import com.zarnth.savr.data.backup.formatBackupTime
import com.zarnth.savr.presentation.setting.SettingEvents
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.zarnth.savr.presentation.setting.SettingState
import com.zarnth.savr.presentation.setting.SettingViewModel

@Composable
fun DataSection(
    state: SettingState,
    viewModel: SettingViewModel,
    onPickFolder: () -> Unit
) {
    Spacer(Modifier.height(12.dp))
    SectionHeader("Data")
    SettingItem(
        icon = R.drawable.backup_db,
        title = "Export bookmarks",
        subtitle = "JSON or HTML",
        onClick = { viewModel.onEvent(SettingEvents.ShowExportSheet) }
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.import_icon,
        title = "Import bookmarks",
        subtitle = "JSON or HTML",
        onClick = { viewModel.onEvent(SettingEvents.ShowImportSheet) }
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.backup_icon,
        title = "Hourly auto backup",
        subtitle = if (state.autoBackupEnabled) {
            val status = when {
                state.backupError != null -> "Problem: ${state.backupError}"
                state.lastBackupTimeText.isNotEmpty() -> state.lastBackupTimeText
                else -> "No backup yet"
            }
            "Keeps the last ${state.backupRetention} backups\n$status"
        } else "Off",
        trailing = {
            Switch(
                checked = state.autoBackupEnabled,
                onCheckedChange = { viewModel.onEvent(SettingEvents.ToggleAutoBackup(it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        onClick = { viewModel.onEvent(SettingEvents.ToggleAutoBackup(!state.autoBackupEnabled)) }
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.backup_db,
        title = "Backup folder",
        subtitle = state.backupFolderName
            ?: if (state.hasBackupFolder) "Folder not accessible. Tap to choose again" else "Not set. Tap to choose",
        onClick = onPickFolder
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.backup_db,
        title = "Backups to keep",
        subtitle = "${state.backupRetention} (older ones are deleted automatically)",
        onClick = { viewModel.onEvent(SettingEvents.ShowRetentionSheet) }
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.backup_icon,
        title = "Back up now",
        subtitle = "Save a copy to the backup folder",
        onClick = { viewModel.onEvent(SettingEvents.BackupNow) }
    )
    Spacer(Modifier.height(4.dp))
    val latestBackup = state.backups.firstOrNull { !it.isSafetyCopy }
    SettingItem(
        icon = R.drawable.import_icon,
        title = "Restore latest backup",
        subtitle = latestBackup?.let { formatBackupTime(it.timeMillis) } ?: "No backups found",
        onClick = { viewModel.onEvent(SettingEvents.RestoreLatest) }
    )
    Spacer(Modifier.height(4.dp))
    SettingItem(
        icon = R.drawable.import_icon,
        title = "Choose a backup to restore",
        subtitle = "Pick an older version",
        onClick = { viewModel.onEvent(SettingEvents.ShowRestoreList) }
    )
}
