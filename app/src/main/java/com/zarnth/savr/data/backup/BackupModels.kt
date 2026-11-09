package com.zarnth.savr.data.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A backup file found in the user's backup folder. */
data class BackupFileInfo(
    val name: String,
    val uri: String,
    val timeMillis: Long,
    val sizeBytes: Long,
    /** True for the automatic copy taken right before a restore replaced the data. */
    val isSafetyCopy: Boolean = false
)

sealed class BackupResult {
    data class Created(val fileName: String) : BackupResult()
    object Unchanged : BackupResult()
    object NothingToBackUp : BackupResult()
    data class Failed(val message: String) : BackupResult()
}

fun formatBackupTime(millis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(millis))
