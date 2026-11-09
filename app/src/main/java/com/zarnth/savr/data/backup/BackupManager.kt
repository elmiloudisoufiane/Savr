package com.zarnth.savr.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.zarnth.savr.data.local.BackupBookmark
import com.zarnth.savr.data.local.BackupCollection
import com.zarnth.savr.data.local.BackupData
import com.zarnth.savr.data.local.BookmarkDatabase
import com.zarnth.savr.link_fetcher.LinkMetadataParser
import com.zarnth.savr.data.local.dao.BookmarkDao
import com.zarnth.savr.data.local.dao.CollectionDao
import com.zarnth.savr.data.local.entity.BookmarkCollectionCrossRef
import com.zarnth.savr.data.local.entity.BookmarkEntity
import com.zarnth.savr.data.local.entity.CollectionEntity
import com.zarnth.savr.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BackupManager(
    private val bookmarkDao: BookmarkDao,
    private val collectionDao: CollectionDao,
    private val settingsRepository: SettingsRepository,
    private val context: Context,
    private val database: BookmarkDatabase
) {
    companion object {
        const val BACKUP_PREFIX = "savr_backup_"
        const val SAFETY_PREFIX = "savr_prerestore_"
        private const val EXTENSION = ".json"
        private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"
        private const val SAFETY_COPIES_TO_KEEP = 3
        private const val WORK_NAME = "savr_hourly_backup"
        private val TIMESTAMP_IN_NAME = Regex("\\d{8}_\\d{6}")
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val backupMutex = Mutex()

    private val _lastBackupTimeMillis = MutableStateFlow(settingsRepository.getLastBackupTime())
    val lastBackupTimeMillis: StateFlow<Long> = _lastBackupTimeMillis.asStateFlow()

    private val _lastError = MutableStateFlow(settingsRepository.getLastBackupError())
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        if (settingsRepository.getAutoBackupEnabled()) {
            scheduleHourlyBackup()
        }
    }

    // ---------------------------------------------------------------- scheduling

    fun enableAutoBackup() {
        settingsRepository.setAutoBackupEnabled(true)
        scheduleHourlyBackup()
    }

    fun disableAutoBackup() {
        settingsRepository.setAutoBackupEnabled(false)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun scheduleHourlyBackup() {
        val request = PeriodicWorkRequest.Builder(BackupWorker::class.java, 1, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    // ---------------------------------------------------------------- folder

    fun hasBackupFolder(): Boolean = settingsRepository.getBackupFolderUri() != null

    fun getBackupFolderName(): String? {
        val uri = settingsRepository.getBackupFolderUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
    }

    fun setBackupFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val old = settingsRepository.getBackupFolderUri()
        if (old != null && old != uri.toString()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        settingsRepository.setBackupFolderUri(uri.toString())
        // Force a fresh backup into the new folder on the next run.
        settingsRepository.setLastBackupHash(null)
        settingsRepository.setLastBackupError(null)
        _lastError.value = null
    }

    private fun backupDir(): DocumentFile? {
        val uri = settingsRepository.getBackupFolderUri() ?: return null
        val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull() ?: return null
        return if (dir.exists() && dir.canWrite()) dir else null
    }

    private fun listFiles(dir: DocumentFile, prefix: String): List<DocumentFile> =
        dir.listFiles()
            .filter { f ->
                val name = f.name
                f.isFile && name != null && name.startsWith(prefix) && name.endsWith(EXTENSION)
            }
            .sortedByDescending { it.name }

    /** Backups (and pre-restore safety copies) in the chosen folder, newest first. */
    fun listBackups(): List<BackupFileInfo> {
        val dir = backupDir() ?: return emptyList()
        val regular = listFiles(dir, BACKUP_PREFIX).map { toInfo(it, isSafety = false) }
        val safety = listFiles(dir, SAFETY_PREFIX).map { toInfo(it, isSafety = true) }
        return (regular + safety).sortedByDescending { it.timeMillis }
    }

    private fun toInfo(file: DocumentFile, isSafety: Boolean): BackupFileInfo {
        val name = file.name.orEmpty()
        return BackupFileInfo(
            name = name,
            uri = file.uri.toString(),
            timeMillis = timeFromName(name) ?: file.lastModified(),
            sizeBytes = file.length(),
            isSafetyCopy = isSafety
        )
    }

    private fun timeFromName(name: String): Long? {
        val stamp = TIMESTAMP_IN_NAME.find(name)?.value ?: return null
        return runCatching { SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).parse(stamp)?.time }.getOrNull()
    }

    private fun timestampNow(): String = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date())

    // ---------------------------------------------------------------- backup

    /**
     * Writes a new backup file into the chosen folder, unless nothing changed since the
     * last one (so quiet hours don't push useful older versions out of the retention window).
     */
    suspend fun backupNow(): BackupResult = backupMutex.withLock {
        withContext(Dispatchers.IO) { performBackup() }
    }

    private suspend fun performBackup(): BackupResult {
        val dir = backupDir()
        if (dir == null) {
            return fail(
                if (hasBackupFolder()) "The backup folder is not accessible. Choose it again."
                else "Choose a backup folder first."
            )
        }
        try {
            val data = buildBackupData()
            // Never overwrite good backups with an empty snapshot.
            if (data.bookmarks.isEmpty() && data.collections.isEmpty()) {
                return BackupResult.NothingToBackUp
            }

            val hash = contentHash(data)
            val hasExisting = listFiles(dir, BACKUP_PREFIX).isNotEmpty()
            if (hasExisting && hash == settingsRepository.getLastBackupHash()) {
                markUpToDate()
                return BackupResult.Unchanged
            }

            val name = "$BACKUP_PREFIX${timestampNow()}$EXTENSION"
            writeFile(dir, name, json.encodeToString(data))
            settingsRepository.setLastBackupHash(hash)
            prune(dir, BACKUP_PREFIX, settingsRepository.getBackupRetention())
            markUpToDate()
            return BackupResult.Created(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(e.message ?: "Backup failed")
        }
    }

    private fun fail(message: String): BackupResult {
        settingsRepository.setLastBackupError(message)
        _lastError.value = message
        return BackupResult.Failed(message)
    }

    private fun markUpToDate() {
        val now = System.currentTimeMillis()
        settingsRepository.setLastBackupTime(now)
        settingsRepository.setLastBackupError(null)
        _lastBackupTimeMillis.value = now
        _lastError.value = null
    }

    private fun writeFile(dir: DocumentFile, name: String, content: String) {
        val file = dir.createFile("application/json", name)
            ?: throw IOException("Could not create a file in the backup folder")
        try {
            val out = context.contentResolver.openOutputStream(file.uri, "w")
                ?: throw IOException("Could not write to the backup folder")
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
    }

    private fun prune(dir: DocumentFile, prefix: String, keep: Int) {
        listFiles(dir, prefix).drop(keep.coerceAtLeast(1)).forEach { runCatching { it.delete() } }
    }

    private fun contentHash(data: BackupData): String {
        val canonical = data.copy(
            exportedAt = 0L,
            bookmarks = data.bookmarks.sortedWith(compareBy({ it.url }, { it.createdAt })),
            collections = data.collections
                .sortedBy { it.name }
                .map { it.copy(bookmarkUrls = it.bookmarkUrls.sorted()) }
        )
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(canonical).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- restore

    /**
     * Replaces ALL current bookmarks and collections with the contents of the given backup.
     * The backup is validated first, and a safety copy of the current data is written to the
     * backup folder before anything is deleted. The replace runs in a single DB transaction,
     * so a failure leaves the current data untouched.
     *
     * @return true if a safety copy of the previous data was saved.
     */
    suspend fun restoreFromBackup(uriString: String): Boolean = backupMutex.withLock {
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(Uri.parse(uriString))
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Could not read the selected backup")

            val parsed = json.decodeFromString<BackupData>(text)
            if (parsed.bookmarks.isEmpty() && parsed.collections.isEmpty()) {
                throw IOException("This backup is empty. Nothing was changed.")
            }

            var safetyCopySaved = false
            val current = buildBackupData()
            if (current.bookmarks.isNotEmpty() || current.collections.isNotEmpty()) {
                val dir = backupDir() ?: throw IOException("The backup folder is not accessible")
                writeFile(dir, "$SAFETY_PREFIX${timestampNow()}$EXTENSION", json.encodeToString(current))
                prune(dir, SAFETY_PREFIX, SAFETY_COPIES_TO_KEEP)
                safetyCopySaved = true
            }

            database.withTransaction {
                collectionDao.deleteAllCrossRefs()
                collectionDao.deleteAllCollections()
                bookmarkDao.deleteAllBookmarks()
                importFromJson(text)
            }
            safetyCopySaved
        }
    }

    // ---------------------------------------------------------------- export / import helpers

    private suspend fun buildBackupData(): BackupData {
        val bookmarks = bookmarkDao.getAllBookmarksForBackupOnce()
        val collections = collectionDao.getAllCollectionsOnce()
        return BackupData(
            bookmarks = bookmarks.map {
                BackupBookmark(
                    url = it.url,
                    title = it.title,
                    description = it.description,
                    imageUrl = it.imageUrl,
                    createdAt = it.createdAt,
                    isCollectionOnly = it.isCollectionOnly,
                    isPinned = it.isPinned,
                    pinnedAt = it.pinnedAt
                )
            },
            collections = buildBackupCollections(collections)
        )
    }

    suspend fun generateBackupJson(): String = json.encodeToString(buildBackupData())

    private suspend fun buildBackupCollections(collections: List<CollectionEntity>): List<BackupCollection> {
        val byId = collections.associateBy { it.id }
        return orderParentsFirst(collections).mapNotNull { collection ->
            if (collection.name.isBlank()) return@mapNotNull null
            BackupCollection(
                name = collection.name,
                bookmarkUrls = collectionDao.getBookmarkUrlsForCollection(collection.id),
                pinnedBookmarkUrls = collectionDao.getPinnedBookmarkUrlsForCollection(collection.id),
                parentName = collection.parentCollectionId?.let { byId[it]?.name }
            )
        }
    }

    private fun orderParentsFirst(collections: List<CollectionEntity>): List<CollectionEntity> {
        val childrenOf = collections.groupBy { it.parentCollectionId }
        val ordered = mutableListOf<CollectionEntity>()
        val visited = mutableSetOf<Long>()
        fun walk(parentId: Long?) {
            for (child in childrenOf[parentId].orEmpty().sortedByDescending { it.createdAt }) {
                if (visited.add(child.id)) {
                    ordered.add(child)
                    walk(child.id)
                }
            }
        }
        walk(null)
        for (c in collections) {
            if (visited.add(c.id)) ordered.add(c)
        }
        return ordered
    }

    suspend fun generateExportHtml(): String {
        val bookmarks = bookmarkDao.getBookmarksOnce()
        val collections = collectionDao.getAllCollectionsRaw().first()
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        sb.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        sb.appendLine("<TITLE>Savr Bookmarks</TITLE>")
        sb.appendLine("<H1>Savr Bookmarks</H1>")
        sb.appendLine("<DL><p>")

        val byId = collections.associateBy { it.id }
        val childrenOf = collections.groupBy { it.parentCollectionId }
        val written = mutableSetOf<Long>()
        val assigned = mutableSetOf<Long>()

        suspend fun appendLinks(collectionId: Long, indent: String) {
            for (bm in bookmarks.filter { it.url in collectionDao.getBookmarkUrlsForCollection(collectionId) }) {
                assigned.add(bm.id)
                sb.appendLine("$indent<DT><A HREF=\"${escapeHtml(bm.url)}\"${if (bm.title != null) ">${escapeHtml(bm.title)}" else ">${escapeHtml(bm.url)}"}</A>")
            }
        }

        suspend fun appendFolder(c: CollectionEntity, indent: String) {
            if (!written.add(c.id)) return
            sb.appendLine("$indent<DT><H3>${escapeHtml(c.name)}")
            sb.appendLine("$indent<DL><p>")
            appendLinks(c.id, indent)
            for (child in childrenOf[c.id].orEmpty().sortedByDescending { it.createdAt }) {
                if (written.contains(child.id)) continue
                appendFolder(child, "$indent    ")
            }
            sb.appendLine("$indent</DL><p>")
        }

        suspend fun walkLevel(parentId: Long?, indent: String) {
            for (c in childrenOf[parentId].orEmpty().sortedByDescending { it.createdAt }) {
                appendFolder(c, indent)
            }
        }

        walkLevel(null, "")
        for (c in collections) {
            if (!written.contains(c.id)) appendFolder(c, "")
        }

        val unassigned = bookmarks.filter { it.id !in assigned }
        if (unassigned.isNotEmpty()) {
            sb.appendLine("<DT><H3>Uncategorized</H3>")
            sb.appendLine("<DL><p>")
            for (bm in unassigned) {
                sb.appendLine("<DT><A HREF=\"${escapeHtml(bm.url)}\"${if (bm.title != null) ">${escapeHtml(bm.title)}" else ">${escapeHtml(bm.url)}"}</A>")
            }
            sb.appendLine("</DL><p>")
        }

        sb.appendLine("</DL><p>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    suspend fun importFromBrowserBookmarks(html: String): BrowserImportResult {
        val parser = BookmarkParser()
        val (parsedBookmarks, collectionNames) = parser.parse(html)

        val existingUrls = bookmarkDao.getBookmarksOnce().map { it.url }.toSet()
        var imported = 0
        var skipped = 0

        for (pb in parsedBookmarks) {
            if (pb.url in existingUrls) {
                skipped++
                continue
            }
            bookmarkDao.insertWithReturn(BookmarkEntity(url = pb.url, title = pb.title, description = null, imageUrl = null))
            imported++
        }

        val existingCollections = collectionDao.getAllCollectionsRaw().first().associateBy { it.name }
        val nameToId = existingCollections.mapValues { it.value.id }.toMutableMap()

        val bookmarkMap = bookmarkDao.getBookmarksOnce().associateBy { it.url }

        for (name in collectionNames) {
            val id = nameToId.getOrPut(name) {
                collectionDao.insertCollection(CollectionEntity(name = name))
            }
            parsedBookmarks.filter { it.collection == name }.forEach { pb ->
                bookmarkMap[pb.url]?.let { bm ->
                    collectionDao.addBookmarkToCollection(BookmarkCollectionCrossRef(bm.id, id))
                }
            }
        }

        return BrowserImportResult(imported = imported, skipped = skipped, collections = collectionNames.size)
    }

    suspend fun fetchMissingImages() {
        val parser = LinkMetadataParser()
        val missing = bookmarkDao.getBookmarksMissingMetadataOnce()
        withContext(Dispatchers.IO) {
            for (bm in missing) {
                try {
                    val meta = parser.parse(bm.url)
                    if (meta != null) {
                        bookmarkDao.updateMetadata(
                            id = bm.id,
                            title = meta.title?.takeIf { it.isNotBlank() } ?: bm.title,
                            description = meta.description?.takeIf { it.isNotBlank() } ?: bm.description,
                            imageUrl = meta.imageUrl?.takeIf { it.isNotBlank() } ?: bm.imageUrl
                        )
                    }
                } catch (_: Exception) { }
            }
        }
    }

    suspend fun importFromJson(jsonString: String) {
        val backupData = json.decodeFromString<BackupData>(jsonString)

        // url -> bookmark entity, kept up to date as rows are inserted so that
        // collection links below always resolve (even for URLs that are missing
        // from the backup's top-level bookmarks array).
        val bookmarkMap = bookmarkDao.getBookmarksOnce().associateBy { it.url }.toMutableMap()

        suspend fun ensureBookmark(url: String, source: BackupBookmark? = null): BookmarkEntity? {
            bookmarkMap[url]?.let { existing ->
                if (source?.isPinned == true) {
                    bookmarkDao.setPinned(existing.id, true, source.pinnedAt)
                }
                return existing
            }
            val hidden = bookmarkDao.findHiddenByUrl(url)
            val entity: BookmarkEntity? = if (hidden != null) {
                bookmarkDao.unhideBookmark(hidden.id, hidden.title, hidden.description, hidden.imageUrl, hidden.createdAt)
                bookmarkDao.getBookmarkByUrl(url)
            } else {
                val insertedId = bookmarkDao.insertWithReturn(BookmarkEntity(
                    url = url,
                    title = source?.title,
                    description = source?.description,
                    imageUrl = source?.imageUrl,
                    createdAt = source?.createdAt ?: System.currentTimeMillis(),
                    isCollectionOnly = source?.isCollectionOnly ?: true,
                    isPinned = source?.isPinned ?: false,
                    pinnedAt = source?.pinnedAt
                ))
                bookmarkDao.getBookmarkByUrl(url)
                    ?: BookmarkEntity(id = insertedId, url = url, title = source?.title, description = source?.description, imageUrl = source?.imageUrl, createdAt = source?.createdAt ?: System.currentTimeMillis(), isCollectionOnly = source?.isCollectionOnly ?: true, isPinned = source?.isPinned ?: false, pinnedAt = source?.pinnedAt)
            }
            if (entity != null) bookmarkMap[url] = entity
            return entity
        }

        for (b in backupData.bookmarks) {
            ensureBookmark(b.url, b)
        }

        val existingCollections = collectionDao.getAllCollectionsOnce().associateBy { it.name }
        val collectionNameToId = existingCollections.mapValues { it.value.id }.toMutableMap()

        suspend fun restoreCollection(c: BackupCollection) {
            val parentId = c.parentName?.let { collectionNameToId[it] }
            val collectionId = collectionNameToId.getOrPut(c.name) {
                collectionDao.insertCollection(CollectionEntity(name = c.name, parentCollectionId = parentId))
            }
            c.bookmarkUrls.forEach { url ->
                ensureBookmark(url)?.let { bm ->
                    collectionDao.addBookmarkToCollection(BookmarkCollectionCrossRef(bm.id, collectionId))
                }
            }
            if (c.pinnedBookmarkUrls.isNotEmpty()) {
                val base = System.currentTimeMillis()
                c.pinnedBookmarkUrls.forEachIndexed { index, url ->
                    ensureBookmark(url)?.let { bm ->
                        collectionDao.setPinnedInCollection(bm.id, collectionId, true, base + index)
                    }
                }
            }
        }

        val pending = backupData.collections.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { it.parentName == null || collectionNameToId.containsKey(it.parentName) }
            if (ready.isEmpty()) break
            pending.removeAll(ready.toSet())
            for (c in ready) restoreCollection(c)
        }
        for (c in pending) restoreCollection(c)
    }
}
