package com.zarnth.savr.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zarnth.savr.domain.repository.SettingsRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs roughly every hour (WorkManager periodic work). Android may delay it
 * because of Doze / battery optimisation, so the interval is approximate.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val backupManager: BackupManager by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun doWork(): Result {
        if (!settingsRepository.getAutoBackupEnabled()) return Result.success()
        return when (backupManager.backupNow()) {
            is BackupResult.Failed -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
