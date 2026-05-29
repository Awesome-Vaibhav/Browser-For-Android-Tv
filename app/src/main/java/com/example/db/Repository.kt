package com.example.db

import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val dao: BrowserDao) {

    // Bookmarks
    val bookmarks: Flow<List<Bookmark>> = dao.getAllBookmarks()
    suspend fun addBookmark(bookmark: Bookmark) = dao.insertBookmark(bookmark)
    suspend fun removeBookmark(bookmark: Bookmark) = dao.deleteBookmark(bookmark)
    suspend fun removeBookmarkByUrl(url: String) = dao.deleteBookmarkByUrl(url)
    fun isBookmarked(url: String): Flow<Boolean> = dao.isBookmarked(url)

    // History
    val history: Flow<List<HistoryEntry>> = dao.getAllHistory()
    suspend fun addHistoryEntry(entry: HistoryEntry) = dao.insertHistory(entry)
    suspend fun removeHistoryEntry(id: Long) = dao.deleteHistoryEntry(id)
    suspend fun clearHistory() = dao.clearHistory()

    // Downloads
    val downloads: Flow<List<DownloadItem>> = dao.getAllDownloads()
    suspend fun addDownload(item: DownloadItem): Long = dao.insertDownload(item)
    suspend fun updateDownload(item: DownloadItem) = dao.updateDownload(item)
    suspend fun removeDownload(id: Long) = dao.deleteDownload(id)

    // Extensions
    val extensions: Flow<List<ExtensionScript>> = dao.getAllExtensions()
    val enabledExtensions: Flow<List<ExtensionScript>> = dao.getEnabledExtensions()
    suspend fun addExtension(extension: ExtensionScript) = dao.insertExtension(extension)
    suspend fun updateExtension(extension: ExtensionScript) = dao.updateExtension(extension)
    suspend fun removeExtension(extension: ExtensionScript) = dao.deleteExtension(extension)
}
