package com.zarnth.savr.presentation.setting.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
fun AutoBackupInfoDialog(
    hasFolder: Boolean,
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Enable hourly backup?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append("Savr saves a copy of your bookmarks about once an hour.\n\n")
                    }
                    append("A new copy is only created when something changed, and copies beyond your limit are deleted automatically. ")
                    append("Android may delay backups to save battery, so the timing is approximate.\n\n")
                    append("Backups stay in a folder on your device. Nothing is uploaded.")
                    if (!hasFolder) {
                        append("\n\nNext, you will choose the backup folder.")
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text(if (hasFolder) "Enable" else "Choose folder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
