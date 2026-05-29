package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val status: String, // "NEW", "DOWNLOADING", "COMPLETED", "FAILED"
    val progress: Int, // 0 to 100
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "extensions")
data class ExtensionScript(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val scriptContent: String, // Javascript source
    val isEnabled: Boolean,
    val isUserAdded: Boolean,
    val category: String = "General" // e.g. "AdBlock", "Style", "Automation"
)
