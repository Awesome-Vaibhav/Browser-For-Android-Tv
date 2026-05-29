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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream

sealed class InstallState {
    object Idle : InstallState()
    data class Installing(val message: String) : InstallState()
    data class Success(val message: String) : InstallState()
    data class Error(val message: String) : InstallState()
}

data class BrowserTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "https://www.google.com",
    val title: String = "Google",
    val isIncognito: Boolean = false,
    val isDesktop: Boolean = false,
    val groupName: String? = null
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = BrowserDatabase.getDatabase(application, viewModelScope)
    private val repository = BrowserRepository(db.browserDao())

    // Tabs state list
    private val _tabs = MutableStateFlow<List<BrowserTab>>(listOf(BrowserTab(id = "default_tab", url = "https://www.google.com", title = "Google")))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("default_tab")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _defaultSearchEngine = MutableStateFlow("Google") // Google, Bing, Yahoo, DuckDuckGo
    val defaultSearchEngine: StateFlow<String> = _defaultSearchEngine.asStateFlow()

    private val _zoomLevel = MutableStateFlow(100) // 100, 125, 150, 200
    val zoomLevel: StateFlow<Int> = _zoomLevel.asStateFlow()

    private val _recentlyClosedTabs = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val recentlyClosedTabs: StateFlow<List<Pair<String, String>>> = _recentlyClosedTabs.asStateFlow()

    private val _isFindInPageActive = MutableStateFlow(false)
    val isFindInPageActive: StateFlow<Boolean> = _isFindInPageActive.asStateFlow()

    private val _findInPageQuery = MutableStateFlow("")
    val findInPageQuery: StateFlow<String> = _findInPageQuery.asStateFlow()

    private val _isReadingModeActive = MutableStateFlow(false)
    val isReadingModeActive: StateFlow<Boolean> = _isReadingModeActive.asStateFlow()

    private val _readingContent = MutableStateFlow("")
    val readingContent: StateFlow<String> = _readingContent.asStateFlow()

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
        BOOKMARKS, HISTORY, DOWNLOADS, EXTENSIONS, VOICE_SEARCH, AD_BLOCK_STATS,
        CHROME_MENU, SETTINGS_PANEL, RECENT_TABS, HELP_FEEDBACK, TAB_MANAGER
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

    // Tabs Management functions
    fun createNewTab(url: String = "https://www.google.com", isIncognito: Boolean = false) {
        val newId = java.util.UUID.randomUUID().toString()
        val title = if (isIncognito) "Incognito Tab" else "New Tab"
        val newTab = BrowserTab(id = newId, url = url, title = title, isIncognito = isIncognito)
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newId
        _currentUrl.value = url
        _pageTitle.value = title
    }

    fun addTabToNewGroup(tabId: String, groupName: String) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) {
                it.copy(groupName = groupName)
            } else {
                it
            }
        }
    }

    fun selectTab(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) {
            _activeTabId.value = tabId
            _currentUrl.value = tab.url
            _pageTitle.value = tab.title
        }
    }

    fun closeTab(tabId: String) {
        val tabToClose = _tabs.value.find { it.id == tabId }
        if (tabToClose != null && !tabToClose.isIncognito) {
            _recentlyClosedTabs.value = (listOf(Pair(tabToClose.url, tabToClose.title)) + _recentlyClosedTabs.value).take(15)
        }

        val updatedList = _tabs.value.filter { it.id != tabId }
        if (updatedList.isEmpty()) {
            _tabs.value = listOf(BrowserTab(id = "default_tab", url = "https://www.google.com", title = "Google"))
            _activeTabId.value = "default_tab"
            _currentUrl.value = "https://www.google.com"
            _pageTitle.value = "Google"
        } else {
            _tabs.value = updatedList
            if (_activeTabId.value == tabId) {
                val firstTab = updatedList.first()
                _activeTabId.value = firstTab.id
                _currentUrl.value = firstTab.url
                _pageTitle.value = firstTab.title
            }
        }
    }

    fun toggleDesktopSiteForActiveTab() {
        val activeId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) {
                it.copy(isDesktop = !it.isDesktop)
            } else {
                it
            }
        }
    }

    fun setDefaultSearchEngine(engine: String) {
        _defaultSearchEngine.value = engine
    }

    fun setZoomLevel(level: Int) {
        _zoomLevel.value = level
    }

    fun setFindInPageActive(active: Boolean) {
        _isFindInPageActive.value = active
        if (!active) {
            _findInPageQuery.value = ""
        }
    }

    fun updateFindInPageQuery(query: String) {
        _findInPageQuery.value = query
    }

    fun toggleReadingMode(active: Boolean, pageContent: String = "") {
        _isReadingModeActive.value = active
        if (active) {
            _readingContent.value = pageContent
        } else {
            _readingContent.value = ""
        }
    }

    // Navigation and Browser actions
    fun loadUrl(url: String) {
        var cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return

        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            val isDomain = cleanUrl.contains(".") && !cleanUrl.contains(" ")
            cleanUrl = if (isDomain) {
                "https://$cleanUrl"
            } else {
                val searchBase = when (_defaultSearchEngine.value) {
                    "Bing" -> "https://www.bing.com/search?q="
                    "Yahoo" -> "https://search.yahoo.com/search?p="
                    "DuckDuckGo" -> "https://duckduckgo.com/?q="
                    else -> "https://www.google.com/search?q="
                }
                searchBase + cleanUrl.replace(" ", "+")
            }
        }

        val activeId = _activeTabId.value
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) {
                it.copy(url = cleanUrl)
            } else {
                it
            }
        }
        _currentUrl.value = cleanUrl
    }

    fun updateLoadingState(loading: Boolean, progressPct: Int) {
        _isLoading.value = loading
        _progress.value = progressPct
    }

    fun updateHistoryAndTitle(url: String, title: String) {
        val activeId = _activeTabId.value
        val finalTitle = if (title.isNotEmpty()) title else URLUtil.guessFileName(url, null, null)
        
        _tabs.value = _tabs.value.map {
            if (it.id == activeId) {
                if (!it.isIncognito) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.addHistoryEntry(HistoryEntry(url = url, title = finalTitle))
                    }
                }
                it.copy(url = url, title = finalTitle)
            } else {
                it
            }
        }

        _currentUrl.value = url
        _pageTitle.value = finalTitle
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

    // Dynamic direct extension store installation engine (Chrome Web Store & Firefox AMO)
    private val _installExtensionState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installExtensionState: StateFlow<InstallState> = _installExtensionState.asStateFlow()

    fun clearInstallState() {
        _installExtensionState.value = InstallState.Idle
    }

    // Install direct Firefox AMO / online package link (.xpi, .crx, .zip)
    fun installExtensionFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installExtensionState.value = InstallState.Installing("Downloading Firefox/External extension package...")
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Gecko/20100101 Firefox/120.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Download failed with server status code: ${response.code}")

                val body = response.body ?: throw Exception("Null contents received from Firefox servers.")
                val filename = URLUtil.guessFileName(url, null, null)
                val extName = filename.substringBeforeLast(".").ifEmpty { "Firefox Extension" }
                installExtensionFromArchive(body.byteStream(), extName)
            } catch (e: Exception) {
                Log.e("TVBrowserExtensionStore", "Firefox download failed", e)
                _installExtensionState.value = InstallState.Error("AMO store connection failed: ${e.localizedMessage}")
            }
        }
    }

    // Extract archive (.crx, .xpi, .zip), parse manifest.json, extract scripts and combine
    fun installExtensionFromArchive(inputStream: InputStream, fallbackName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installExtensionState.value = InstallState.Installing("Extracting & reading bundle...")
                val bytes = inputStream.readBytes()

                // Crucial step: find PK\u0003\u0004 header to strip Google's CRX format envelope
                var zipOffset = -1
                for (i in 0 until bytes.size - 3) {
                    if (bytes[i] == 0x50.toByte() &&
                        bytes[i+1] == 0x4B.toByte() &&
                        bytes[i+2] == 0x03.toByte() &&
                        bytes[i+3] == 0x04.toByte()) {
                        zipOffset = i
                        break
                    }
                }

                val zipBytes = if (zipOffset != -1) {
                    bytes.copyOfRange(zipOffset, bytes.size)
                } else {
                    bytes
                }

                val filesMap = mutableMapOf<String, String>()
                val zipStream = ZipInputStream(ByteArrayInputStream(zipBytes))
                var entry = zipStream.nextEntry
                var manifestContent = ""

                while (entry != null) {
                    val name = entry.name.replace("\\", "/").trimStart('/')
                    if (!entry.isDirectory) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        var len: Int
                        while (zipStream.read(buffer).also { len = it } != -1) {
                            out.write(buffer, 0, len)
                        }
                        val contentString = out.toString("UTF-8")

                        if (name == "manifest.json") {
                            manifestContent = contentString
                        } else {
                            filesMap[name] = contentString
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                zipStream.close()

                if (manifestContent.isEmpty()) {
                    _installExtensionState.value = InstallState.Error("No manifest.json found inside the extension package.")
                    return@launch
                }

                val manifestJson = JSONObject(manifestContent)
                val name = manifestJson.optString("name", fallbackName)
                val description = manifestJson.optString("description", "Directly installed Store Extension script.")

                val jsCodeBuilder = StringBuilder()
                val cssCodeBuilder = StringBuilder()

                val contentScriptsArray = manifestJson.optJSONArray("content_scripts")
                if (contentScriptsArray != null && contentScriptsArray.length() > 0) {
                    for (i in 0 until contentScriptsArray.length()) {
                        val scriptObj = contentScriptsArray.getJSONObject(i)

                        // Extracted JS Inject files
                        val jsArray = scriptObj.optJSONArray("js")
                        if (jsArray != null) {
                            for (j in 0 until jsArray.length()) {
                                val jsFile = jsArray.getString(j).replace("\\", "/").trimStart('/')
                                val fileContent = filesMap[jsFile] ?: filesMap.entries.firstOrNull { it.key.endsWith(jsFile) }?.value
                                if (fileContent != null) {
                                    jsCodeBuilder.append("// Inject File: $jsFile\n")
                                    jsCodeBuilder.append(fileContent)
                                    jsCodeBuilder.append("\n\n")
                                }
                            }
                        }

                        // Extracted CSS style injects
                        val cssArray = scriptObj.optJSONArray("css")
                        if (cssArray != null) {
                            for (j in 0 until cssArray.length()) {
                                val cssFile = cssArray.getString(j).replace("\\", "/").trimStart('/')
                                val fileContent = filesMap[cssFile] ?: filesMap.entries.firstOrNull { it.key.endsWith(cssFile) }?.value
                                if (fileContent != null) {
                                    cssCodeBuilder.append(fileContent)
                                    cssCodeBuilder.append("\n")
                                }
                            }
                        }
                    }
                } else {
                    // Try parsing background/scripts fallback
                    val background = manifestJson.optJSONObject("background")
                    val serviceWorker = background?.optString("service_worker", "") ?: ""
                    if (serviceWorker.isNotEmpty()) {
                        val fileContent = filesMap[serviceWorker] ?: filesMap.entries.firstOrNull { it.key.endsWith(serviceWorker) }?.value
                        if (fileContent != null) {
                            jsCodeBuilder.append(fileContent)
                        }
                    } else {
                        val scripts = background?.optJSONArray("scripts")
                        if (scripts != null) {
                            for (j in 0 until scripts.length()) {
                                val jsFile = scripts.getString(j).replace("\\", "/").trimStart('/')
                                val fileContent = filesMap[jsFile] ?: filesMap.entries.firstOrNull { it.key.endsWith(jsFile) }?.value
                                if (fileContent != null) {
                                    jsCodeBuilder.append(fileContent).append("\n")
                                }
                            }
                        }
                    }
                }

                // If CSS style codes are found, wrap them inside dynamic inline stylesheet generator script
                val styleInjectionCode = if (cssCodeBuilder.isNotEmpty()) {
                    val safeCssContent = cssCodeBuilder.toString()
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("$", "\\$")
                    """
                    (function() {
                        let style = document.getElementById('store-extension-css-${name.hashCode()}');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'store-extension-css-${name.hashCode()}';
                            style.innerHTML = `${safeCssContent}`;
                            document.head.appendChild(style);
                        }
                    })();
                    """.trimIndent()
                } else ""

                val bundledScript = styleInjectionCode + "\n" + jsCodeBuilder.toString()

                if (bundledScript.trim().isEmpty()) {
                    _installExtensionState.value = InstallState.Error("This extension has no static content scripts or styles available to inject in Android WebView.")
                    return@launch
                }

                // Inject formatted bundle script to Room DB
                repository.addExtension(
                    ExtensionScript(
                        name = name,
                        description = description,
                        scriptContent = bundledScript,
                        isEnabled = true,
                        isUserAdded = true,
                        category = "Direct Store"
                    )
                )

                _installExtensionState.value = InstallState.Success("Extension '$name' successfully installed and activated!")
                delay(3000)
                _installExtensionState.value = InstallState.Idle
            } catch (e: Exception) {
                Log.e("TVBrowserExtensionStore", "Failed extracting extension packaging", e)
                _installExtensionState.value = InstallState.Error("Failed to import: ${e.localizedMessage}")
            }
        }
    }
}
