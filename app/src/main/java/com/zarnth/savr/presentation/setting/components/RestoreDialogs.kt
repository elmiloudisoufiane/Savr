package com.zarnth.savr.presentation.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zarnth.savr.data.backup.BackupFileInfo
import com.zarnth.savr.data.backup.formatBackupTime

private fun formatSize(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

@Composable
fun RestoreListDialog(
    backups: List<BackupFileInfo>,
    onSelect: (BackupFileInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val latest = backups.firstOrNull { !it.isSafetyCopy }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a backup", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn {
                items(backups) { backup ->
                    val label = when {
                        backup.isSafetyCopy -> "Before a restore"
                        backup == latest -> "Latest"
                        else -> null
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(backup) }
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = formatBackupTime(backup.timeMillis),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = (if (label != null) "$label · " else "") + formatSize(backup.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun RestoreConfirmDialog(
    backup: BackupFileInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = "Your current bookmarks and collections will be replaced with the backup from " +
                    "${formatBackupTime(backup.timeMillis)}.\n\n" +
                    "A safety copy of your current data is saved in the backup folder first.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
