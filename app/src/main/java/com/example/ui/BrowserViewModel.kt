package com.example.ui

import android.app.Application
import android.util.Log
import android.webkit.URLUtil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.Bookmark
import com.example.db.BrowserDatabase
import com.example.db.BrowserRepository
import com.example.db.DownloadItem
import com.example.db.ExtensionScript
import com.example.db.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = BrowserDatabase.getDatabase(application, viewModelScope)
    private val repository = BrowserRepository(db.browserDao())

    // UI state parameters
    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("Google")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    // Virtual Cursor settings
    private val _cursorX = MutableStateFlow(400f)
    val cursorX: StateFlow<Float> = _cursorX.asStateFlow()

    private val _cursorY = MutableStateFlow(300f)
    val cursorY: StateFlow<Float> = _cursorY.asStateFlow()

    private val _cursorMode = MutableStateFlow(true) // Start with cursor mode enabled by default for Android TV
    val cursorMode: StateFlow<Boolean> = _cursorMode.asStateFlow()

    private val _isAdBlockEnabled = MutableStateFlow(true)
    val isAdBlockEnabled: StateFlow<Boolean> = _isAdBlockEnabled.asStateFlow()

    // Active screen overlays (menus)
    private val _activeMenu = MutableStateFlow<MenuType?>(null)
    val activeMenu: StateFlow<MenuType?> = _activeMenu.asStateFlow()

    enum class MenuType {
        BOOKMARKS, HISTORY, DOWNLOADS, EXTENSIONS, VOICE_SEARCH, AD_BLOCK_STATS
    }

    // Room DB StateFlow streams
    val bookmarks: StateFlow<List<Bookmark>> = repository.bookmarks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val historyEntries: StateFlow<List<HistoryEntry>> = repository.history.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val downloads: StateFlow<List<DownloadItem>> = repository.downloads.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val extensions: StateFlow<List<ExtensionScript>> = repository.extensions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val enabledExtensions: StateFlow<List<ExtensionScript>> = repository.enabledExtensions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Keep track of download jobs to handle cancellations if needed
    private val activeDownloadJobs = mutableMapOf<Long, Job>()

    init {
        // Pre-fill history entry for smooth start
        viewModelScope.launch {
            repository.addHistoryEntry(
                HistoryEntry(url = "https://www.google.com", title = "Google")
            )
        }
    }

    // Navigation and Browser actions
    fun loadUrl(url: String) {
        var cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return

        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            // Check if it's a domain search or general Google search
            val isDomain = cleanUrl.contains(".") && !cleanUrl.contains(" ")
            cleanUrl = if (isDomain) {
                "https://$cleanUrl"
            } else {
                "https://www.google.com/search?q=${cleanUrl.replace(" ", "+")}"
            }
        }
        _currentUrl.value = cleanUrl
    }

    fun updateLoadingState(loading: Boolean, progressPct: Int) {
        _isLoading.value = loading
        _progress.value = progressPct
    }

    fun updateHistoryAndTitle(url: String, title: String) {
        _currentUrl.value = url
        _pageTitle.value = if (title.isNotEmpty()) title else URLUtil.guessFileName(url, null, null)
        
        // Add to persistent history
        viewModelScope.launch(Dispatchers.IO) {
            repository.addHistoryEntry(HistoryEntry(url = url, title = _pageTitle.value))
        }
    }

    fun setNavigationCapabilities(back: Boolean, forward: Boolean) {
        _canGoBack.value = back
        _canGoForward.value = forward
    }

    // Bookmarks Toggle
    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val isCurrentlyBookmarked = bookmarks.value.any { it.url == url }
            if (isCurrentlyBookmarked) {
                repository.removeBookmarkByUrl(url)
            } else {
                repository.addBookmark(Bookmark(url = url, title = title))
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeBookmark(bookmark)
        }
    }

    // History management
    fun deleteHistory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeHistoryEntry(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }

    // Toggle menu
    fun toggleMenu(menu: MenuType?) {
        _activeMenu.value = menu
    }

    // Toggle AdBlock Enablement
    fun toggleAdBlock() {
        _isAdBlockEnabled.value = !_isAdBlockEnabled.value
    }

    // Extensions manipulation
    fun toggleExtension(script: ExtensionScript) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateExtension(script.copy(isEnabled = !script.isEnabled))
        }
    }

    fun deleteExtension(script: ExtensionScript) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeExtension(script)
        }
    }

    fun addCustomExtension(name: String, desc: String, scriptVal: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addExtension(
                ExtensionScript(
                    name = name,
                    description = desc,
                    scriptContent = scriptVal,
                    isEnabled = true,
                    isUserAdded = true,
                    category = "User Script"
                )
            )
        }
    }

    // Virtual Cursor control
    fun toggleCursorMode() {
        _cursorMode.value = !_cursorMode.value
    }

    // Moves virtual cursor. Clamps it within screen dimensions roughly matching typical mobile/tv displays
    fun moveCursor(dx: Float, dy: Float, screenWidth: Float = 1920f, screenHeight: Float = 1080f) {
        _cursorX.value = (_cursorX.value + dx).coerceIn(0f, screenWidth)
        _cursorY.value = (_cursorY.value + dy).coerceIn(0f, screenHeight)
    }

    fun setCursorPosition(x: Float, y: Float) {
        _cursorX.value = x
        _cursorY.value = y
    }

    // Native Downloader logic
    fun startFileDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        
        viewModelScope.launch(Dispatchers.IO) {
            // Insert status to Database
            val downloadItem = DownloadItem(
                url = url,
                fileName = fileName,
                status = "DOWNLOADING",
                progress = 0,
                filePath = ""
            )
            
            val dbId = repository.addDownload(downloadItem)
            
            // Launch background coroutine job for actual downloading
            val downloadJob = launch(Dispatchers.IO) {
                var outputStream: FileOutputStream? = null
                var inputStream: InputStream? = null
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent ?: "Mozilla/5.0 (Android TVBrowser)")
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Failed to fetch download url: Status " + response.code)

                    val body = response.body
                    if (body == null) throw Exception("Empty download content")

                    val fileLength = body.contentLength()
                    
                    // Direct saves inside application files dir for TV Lightweight storage access
                    val cacheDir = getApplication<Application>().filesDir
                    val destFile = File(cacheDir, fileName)
                    outputStream = FileOutputStream(destFile)
                    inputStream = body.byteStream()
                    
                    val buffer = ByteArray(4096)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    var lastUpdatePercent = 0

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (fileLength > 0) {
                            val percent = ((totalBytesRead * 100) / fileLength).toInt()
                            if (percent - lastUpdatePercent >= 5 || percent == 100) {
                                lastUpdatePercent = percent
                                repository.updateDownload(
                                    downloadItem.copy(
                                        id = dbId,
                                        progress = percent,
                                        status = if (percent == 100) "COMPLETED" else "DOWNLOADING",
                                        filePath = destFile.absolutePath
                                    )
                                )
                            }
                        }
                    }
                    
                    // Mark as complete
                    repository.updateDownload(
                        downloadItem.copy(
                            id = dbId,
                            progress = 100,
                            status = "COMPLETED",
                            filePath = destFile.absolutePath
                        )
                    )
                } catch (e: Exception) {
                    Log.e("TVDownloader", "Download failed", e)
                    repository.updateDownload(
                        downloadItem.copy(
                            id = dbId,
                            status = "FAILED",
                            progress = 0
                        )
                    )
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            }
            activeDownloadJobs[dbId] = downloadJob
        }
    }

    fun cancelDownload(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            activeDownloadJobs[id]?.cancel()
            repository.removeDownload(id)
        }
    }
}
