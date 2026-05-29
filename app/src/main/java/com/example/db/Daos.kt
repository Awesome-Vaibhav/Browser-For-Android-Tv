package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserDao {

    // --- Bookmarks ---
    @Query("SELECT * FROM bookmarks ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteBookmarkByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url LIMIT 1)")
    fun isBookmarked(url: String): Flow<Boolean>


    // --- History ---
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 500")
    fun getAllHistory(): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistoryEntry(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearHistory()


    // --- Downloads ---
    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(item: DownloadItem): Long

    @Update
    suspend fun updateDownload(item: DownloadItem)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Long)


    // --- Extensions ---
    @Query("SELECT * FROM extensions")
    fun getAllExtensions(): Flow<List<ExtensionScript>>

    @Query("SELECT * FROM extensions WHERE isEnabled = 1")
    fun getEnabledExtensions(): Flow<List<ExtensionScript>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtension(extension: ExtensionScript)

    @Update
    suspend fun updateExtension(extension: ExtensionScript)

    @Delete
    suspend fun deleteExtension(extension: ExtensionScript)
}
