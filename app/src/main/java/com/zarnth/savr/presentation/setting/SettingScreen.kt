@file:OptIn(ExperimentalMaterial3Api::class)

package com.zarnth.savr.presentation.setting

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zarnth.savr.openChromeTab
import com.zarnth.savr.presentation.home.components.LoadingProgress
import com.zarnth.savr.presentation.setting.components.AboutSection
import com.zarnth.savr.presentation.setting.components.AutoBackupInfoDialog
import com.zarnth.savr.presentation.setting.components.CommunitySection
import com.zarnth.savr.presentation.setting.components.DataSection
import com.zarnth.savr.presentation.setting.components.GeneralSection
import com.zarnth.savr.presentation.setting.components.InfoDialog
import com.zarnth.savr.presentation.setting.components.LegalSection
import com.zarnth.savr.presentation.setting.components.OptionSheet
import com.zarnth.savr.presentation.setting.components.RestoreConfirmDialog
import com.zarnth.savr.presentation.setting.components.RestoreListDialog
import com.zarnth.savr.presentation.setting.components.RadioOptionSheet
import com.zarnth.savr.domain.model.BottomBarStyle
import com.zarnth.savr.presentation.setting.components.ThemeSection
import com.zarnth.savr.ui.theme.ThemeMode
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingScreen(
    viewModel: SettingViewModel = koinViewModel(),
    onOpenCrashLogs: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            val json = (state.exportState as? ExportState.Ready)?.json
            if (json != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
        }
        viewModel.onEvent(SettingEvents.DismissExport)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val json =
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            if (json.isNotBlank()) viewModel.onEvent(SettingEvents.ImportData(json))
        }
    }

    val importBrowserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            val html =
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            if (html.isNotBlank()) viewModel.onEvent(SettingEvents.ImportBrowserBookmarks(html))
        }
    }

    val exportHtmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri: Uri? ->
        if (uri != null) {
            val html = (state.browserExportState as? ExportState.Ready)?.json
            if (html != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
            }
        }
        viewModel.onEvent(SettingEvents.DismissBrowserExport)
    }

    var enableAfterPick by remember { mutableStateOf(false) }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            viewModel.onEvent(SettingEvents.BackupFolderSelected(uri, enableAfterPick))
        }
        enableAfterPick = false
    }
    val pickFolder = {
        folderLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.onEvent(SettingEvents.RefreshBackups)
    }

    LaunchedEffect(state.exportState) {
        if (state.exportState is ExportState.Ready) {
            exportLauncher.launch("savr_backup.json")
        }
    }

    LaunchedEffect(state.browserExportState) {
        if (state.browserExportState is ExportState.Ready) {
            exportHtmlLauncher.launch("savr_bookmarks.html")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ThemeSection(state, viewModel)
            GeneralSection(state, viewModel)
            DataSection(state, viewModel, onPickFolder = { pickFolder() })
            CommunitySection(context)
            LegalSection(context)
            AboutSection(
                versionName,
                onOpenCrashLogs = onOpenCrashLogs,
                onTestCrash = { viewModel.onEvent(SettingEvents.TestCrash) }
            )
        }

        LoadingProgress(
            isLoading = state.exportState is ExportState.Loading ||
                state.browserExportState is ExportState.Loading ||
                state.isBusy
        )
    }

    if (state.showThemeSheet) {
        RadioOptionSheet(
            title = "Choose Theme",
            options = listOf(
                "Light" to ThemeMode.LIGHT,
                "Dark" to ThemeMode.DARK,
                "Amoled" to ThemeMode.AMOLED,
                "System default" to ThemeMode.SYSTEM
            ),
            current = state.themeMode,
            onSelect = { viewModel.onEvent(SettingEvents.SelectTheme(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideThemeSheet) }
        )
    }

    if (state.showBottomBarSheet) {
        RadioOptionSheet(
            title = "Bottom bar style",
            options = listOf(
                "Default" to BottomBarStyle.MATERIAL,
                "iPhone style" to BottomBarStyle.IOS
            ),
            current = state.bottomBarStyle,
            onSelect = { viewModel.onEvent(SettingEvents.SelectBottomBarStyle(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideBottomBarSheet) }
        )
    }

    if (state.showTapActionSheet) {
        RadioOptionSheet(
            title = "On tap",
            options = listOf(
                "Show preview" to TapAction.SHOW_PREVIEW,
                "Open in browser" to TapAction.OPEN_BROWSER,
                "Copy link" to TapAction.COPY_LINK
            ),
            current = state.tapAction,
            onSelect = { viewModel.onEvent(SettingEvents.SelectTapAction(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideTapActionSheet) }
        )
    }

    if (state.showViewModeSheet) {
        RadioOptionSheet(
            title = "View mode",
            options = listOf(
                "Grid" to ViewMode.GRID,
                "List" to ViewMode.LIST
            ),
            current = state.viewMode,
            onSelect = { viewModel.onEvent(SettingEvents.ToggleViewMode(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideViewModeSheet) }
        )
    }

    val exportError = state.exportState as? ExportState.Error
    if (exportError != null) {
        InfoDialog(
            title = "Export failed",
            text = exportError.message ?: "Export failed",
            onDismiss = { viewModel.onEvent(SettingEvents.DismissExport) }
        )
    }

    if (state.importState is ImportState.Success) {
        InfoDialog(
            title = "Import successful",
            text = "Your bookmarks and collections have been restored.",
            onDismiss = { viewModel.onEvent(SettingEvents.DismissImportResult) }
        )
    }

    val importError = state.importState as? ImportState.Error
    if (importError != null) {
        InfoDialog(
            title = "Import failed",
            text = importError.message ?: "Import failed",
            onDismiss = { viewModel.onEvent(SettingEvents.DismissImportResult) }
        )
    }

    val browserImportSuccess = state.browserImportState as? BrowserImportState.Success
    if (browserImportSuccess != null) {
        InfoDialog(
            title = "Browser import complete",
            text = "Imported ${browserImportSuccess.imported} bookmarks into ${browserImportSuccess.collections} folders (${browserImportSuccess.skipped} duplicates skipped).",
            onDismiss = { viewModel.onEvent(SettingEvents.DismissBrowserImportResult) }
        )
    }

    val browserImportError = state.browserImportState as? BrowserImportState.Error
    if (browserImportError != null) {
        InfoDialog(
            title = "Browser import failed",
            text = browserImportError.message,
            onDismiss = { viewModel.onEvent(SettingEvents.DismissBrowserImportResult) }
        )
    }

    val browserExportError = state.browserExportState as? ExportState.Error
    if (browserExportError != null) {
        InfoDialog(
            title = "Export as HTML failed",
            text = browserExportError.message ?: "Export failed",
            onDismiss = { viewModel.onEvent(SettingEvents.DismissBrowserExport) }
        )
    }

    if (state.showExportSheet) {
        OptionSheet(
            title = "Export as",
            options = listOf(
                "JSON" to {
                    viewModel.onEvent(SettingEvents.HideExportSheet)
                    viewModel.onEvent(SettingEvents.ExportData)
                },
                "HTML" to {
                    viewModel.onEvent(SettingEvents.HideExportSheet)
                    viewModel.onEvent(SettingEvents.ExportBrowserBookmarks)
                }
            ),
            onDismiss = { viewModel.onEvent(SettingEvents.HideExportSheet) }
        )
    }

    if (state.showImportSheet) {
        OptionSheet(
            title = "Import from",
            options = listOf(
                "JSON" to {
                    viewModel.onEvent(SettingEvents.HideImportSheet)
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Download/Savr"
                            ))
                        }
                    }
                    importLauncher.launch(intent)
                },
                "HTML" to {
                    viewModel.onEvent(SettingEvents.HideImportSheet)
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/html"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "text/plain"))
                    }
                    importBrowserLauncher.launch(intent)
                }
            ),
            onDismiss = { viewModel.onEvent(SettingEvents.HideImportSheet) }
        )
    }

    if (state.showAutoBackupInfoDialog) {
        AutoBackupInfoDialog(
            hasFolder = state.hasBackupFolder,
            onEnable = {
                if (state.hasBackupFolder) {
                    viewModel.onEvent(SettingEvents.ConfirmAutoBackupEnable)
                } else {
                    enableAfterPick = true
                    viewModel.onEvent(SettingEvents.DismissAutoBackupInfoDialog)
                    pickFolder()
                }
            },
            onDismiss = { viewModel.onEvent(SettingEvents.DismissAutoBackupInfoDialog) }
        )
    }

    if (state.showRetentionSheet) {
        RadioOptionSheet(
            title = "Backups to keep",
            options = listOf(3, 7, 14, 30).map { "$it backups" to it },
            current = state.backupRetention,
            onSelect = { viewModel.onEvent(SettingEvents.SelectRetention(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideRetentionSheet) }
        )
    }

    if (state.showRestoreList) {
        RestoreListDialog(
            backups = state.backups,
            onSelect = { viewModel.onEvent(SettingEvents.RequestRestore(it)) },
            onDismiss = { viewModel.onEvent(SettingEvents.HideRestoreList) }
        )
    }

    val pendingRestore = state.pendingRestore
    if (pendingRestore != null) {
        RestoreConfirmDialog(
            backup = pendingRestore,
            onConfirm = { viewModel.onEvent(SettingEvents.ConfirmRestore) },
            onDismiss = { viewModel.onEvent(SettingEvents.CancelRestore) }
        )
    }

    val backupMessage = state.backupMessage
    if (backupMessage != null) {
        InfoDialog(
            title = "Backup & restore",
            text = backupMessage,
            onDismiss = { viewModel.onEvent(SettingEvents.DismissBackupMessage) }
        )
    }
}
