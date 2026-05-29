package com.example.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import android.view.MotionEvent
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.db.Bookmark
import com.example.db.DownloadItem
import com.example.db.ExtensionScript
import com.example.db.HistoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Observe state variables
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val pageTitle by viewModel.pageTitle.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()

    val cursorX by viewModel.cursorX.collectAsStateWithLifecycle()
    val cursorY by viewModel.cursorY.collectAsStateWithLifecycle()
    val cursorMode by viewModel.cursorMode.collectAsStateWithLifecycle()
    val isAdBlockEnabled by viewModel.isAdBlockEnabled.collectAsStateWithLifecycle()
    val activeMenu by viewModel.activeMenu.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val historyEntries by viewModel.historyEntries.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val enabledExtensions by viewModel.enabledExtensions.collectAsStateWithLifecycle()

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val isFindInPageActive by viewModel.isFindInPageActive.collectAsStateWithLifecycle()
    val findInPageQuery by viewModel.findInPageQuery.collectAsStateWithLifecycle()
    val isReadingModeActive by viewModel.isReadingModeActive.collectAsStateWithLifecycle()
    val readingContent by viewModel.readingContent.collectAsStateWithLifecycle()
    val zoomLevel by viewModel.zoomLevel.collectAsStateWithLifecycle()
    val recentlyClosedTabs by viewModel.recentlyClosedTabs.collectAsStateWithLifecycle()
    val defaultSearchEngine by viewModel.defaultSearchEngine.collectAsStateWithLifecycle()

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var addressInput by remember { mutableStateOf(currentUrl) }

    var layoutWidthDp by remember { mutableStateOf(1280f) }
    var layoutHeightDp by remember { mutableStateOf(720f) }

    // Sync address input when url changes externally
    LaunchedEffect(currentUrl) {
        addressInput = currentUrl
    }

    // Speech recognition launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            spokenText?.let {
                viewModel.loadUrl(it)
                viewModel.toggleMenu(null)
            }
        }
    }

    // Helper functions for cursor clicking WebView
    fun simulateWebViewTouch(x: Float, y: Float) {
        webViewInstance?.let { webView ->
            val scale = density.density
            val rawX = x * scale
            val rawY = y * scale

            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()

            val downEvent = MotionEvent.obtain(
                downTime, eventTime,
                MotionEvent.ACTION_DOWN, rawX, rawY, 0
            )
            webView.dispatchTouchEvent(downEvent)

            val upEvent = MotionEvent.obtain(
                downTime, eventTime + 50,
                MotionEvent.ACTION_UP, rawX, rawY, 0
            )
            webView.dispatchTouchEvent(upEvent)

            downEvent.recycle()
            upEvent.recycle()
        }
    }

    fun simulateScreenTouch(x: Float, y: Float) {
        val activity = context as? android.app.Activity
        val decorView = activity?.window?.decorView
        if (decorView != null) {
            val scale = density.density
            val rawX = x * scale
            val rawY = y * scale

            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()

            val downEvent = MotionEvent.obtain(
                downTime, eventTime,
                MotionEvent.ACTION_DOWN, rawX, rawY, 0
            )
            decorView.dispatchTouchEvent(downEvent)

            val upEvent = MotionEvent.obtain(
                downTime, eventTime + 50,
                MotionEvent.ACTION_UP, rawX, rawY, 0
            )
            decorView.dispatchTouchEvent(upEvent)

            downEvent.recycle()
            upEvent.recycle()
        } else {
            simulateWebViewTouch(x, y)
        }
    }

    // Key event dispatcher for D-pad Virtual Mouse Cursor movement and scrolling
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Request focus to intercept TV remote keys
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121214))
            .focusRequester(focusRequester)
            .focusable()
            .onSizeChanged { size ->
                val densityVal = density.density
                if (densityVal > 0f) {
                    layoutWidthDp = size.width / densityVal
                    layoutHeightDp = size.height / densityVal
                }
            }
            .onKeyEvent { keyEvent ->
                val isBackKey = keyEvent.key == Key.Back
                if (activeMenu != null && !cursorMode && !isBackKey) return@onKeyEvent false

                val isKeyDown = keyEvent.type == KeyEventType.KeyDown
                if (isKeyDown) {
                    val stepMultiplier = if (keyEvent.nativeKeyEvent.metaState and android.view.KeyEvent.META_SHIFT_ON != 0) 3.5f else 1f
                    val step = 16f * stepMultiplier

                    when (keyEvent.key) {
                        Key.DirectionUp -> {
                            if (cursorMode) {
                                if (cursorY > 80f && cursorY < 180f) {
                                    webViewInstance?.scrollBy(0, -150)
                                }
                                viewModel.moveCursor(0f, -step, layoutWidthDp, layoutHeightDp)
                                true
                            } else false
                        }
                        Key.DirectionDown -> {
                            if (cursorMode) {
                                if (cursorY > layoutHeightDp - 100f) {
                                    webViewInstance?.scrollBy(0, 150)
                                }
                                viewModel.moveCursor(0f, step, layoutWidthDp, layoutHeightDp)
                                true
                            } else false
                        }
                        Key.DirectionLeft -> {
                            if (cursorMode) {
                                viewModel.moveCursor(-step, 0f, layoutWidthDp, layoutHeightDp)
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (cursorMode) {
                                viewModel.moveCursor(step, 0f, layoutWidthDp, layoutHeightDp)
                                true
                            } else false
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (cursorMode) {
                                simulateScreenTouch(cursorX, cursorY)
                                true
                            } else false
                        }
                        Key.Back -> {
                            // Back button action
                            if (activeMenu != null) {
                                viewModel.toggleMenu(null)
                                true
                            } else if (canGoBack) {
                                webViewInstance?.goBack()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Browser control head bar (optimized for TV layout & visible focus elements)
            NavigationBarRow(
                addressValue = addressInput,
                onAddressChanged = { addressInput = it },
                onSearchTriggered = { viewModel.loadUrl(addressInput) },
                onBack = { webViewInstance?.goBack() },
                onForward = { webViewInstance?.goForward() },
                onReload = { webViewInstance?.reload() },
                isBookmarked = bookmarks.any { it.url == currentUrl },
                onBookmarkToggle = { viewModel.toggleBookmark(currentUrl, pageTitle) },
                cursorEnabled = cursorMode,
                onCursorModeToggled = { viewModel.toggleCursorMode() },
                adBlockActive = isAdBlockEnabled,
                onAdBlockToggle = { viewModel.toggleAdBlock() },
                onMenuSelect = { viewModel.toggleMenu(it) },
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isLoading = isLoading,
                onVoiceTrigger = {
                    try {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak web URL or Google Search query...")
                        }
                        speechLauncher.launch(intent)
                    } catch (e: Exception) {
                        Log.e("TVBrowser", "Voice recognition not supported", e)
                    }
                }
            )

            // Dynamic Tab Bar Row
            val activeTabInstance = tabs.find { it.id == activeTabId }
            val isIncognitoThemeActive = activeTabInstance?.isIncognito == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isIncognitoThemeActive) Color(0xFF241533) else Color(0xFF131315))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeTabId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isActive) {
                                    if (tab.isIncognito) Color(0xFF4C2A72) else Color(0xFF2C2C30)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .border(
                                1.dp,
                                if (isActive) {
                                    if (tab.isIncognito) Color(0xFFBB86FC) else Color(0xFFFF9800)
                                } else {
                                    Color(0xFF2C2C30)
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { viewModel.selectTab(tab.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (tab.isIncognito) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = "Incognito Tab Indicator",
                                    tint = Color(0xFFCE93D8),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = if (tab.title.length > 20) tab.title.take(17) + "..." else tab.title,
                                color = if (isActive) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Tab Control",
                                tint = if (isActive) Color.White else Color.Gray,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.closeTab(tab.id) }
                            )
                        }
                    }
                }
                
                IconButton(
                    onClick = { viewModel.createNewTab() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create New Browsing Tab",
                        tint = Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Find In Page Bar Panel
            if (isFindInPageActive) {
                var localMatchText by remember { mutableStateOf("0/0") }
                
                LaunchedEffect(findInPageQuery, webViewInstance) {
                    webViewInstance?.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                        localMatchText = if (numberOfMatches > 0) {
                            "${activeMatchOrdinal + 1}/$numberOfMatches"
                        } else {
                            "0/0"
                        }
                    }
                    if (findInPageQuery.isNotEmpty()) {
                        webViewInstance?.findAllAsync(findInPageQuery)
                    } else {
                        webViewInstance?.clearMatches()
                        localMatchText = "0/0"
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    color = Color(0xFF28282B),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Find Icon indicator",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                        
                        BasicTextField(
                            value = findInPageQuery,
                            onValueChange = { viewModel.updateFindInPageQuery(it) },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color(0xFFFF9800)),
                            modifier = Modifier
                                .weight(1f)
                                .focusProperties { canFocus = !cursorMode }
                        )

                        Text(
                            text = localMatchText,
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        IconButton(
                            onClick = { webViewInstance?.findNext(false) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Query Previous Match",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { webViewInstance?.findNext(true) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Query Next Match",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { viewModel.setFindInPageActive(false) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Find Query Bar",
                                tint = Color.LightGray
                            )
                        }
                    }
                }
            }

            // Web Loading Progress indicator strip
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFFFF9800),
                    trackColor = Color.Transparent
                )
            } else {
                Spacer(modifier = Modifier.height(3.dp))
            }

            // 2. WebView Frame Window
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView<WebView>(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // Setup options
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                databaseEnabled = true
                                supportZoom()
                                displayZoomControls = false
                                builtInZoomControls = true
                                mediaPlaybackRequiresUserGesture = false
                                useWideViewPort = true
                                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            }

                            // Setup Custom Download listener
                            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                if (url.contains(".crx") || url.contains(".xpi") || mimetype == "application/x-xpinstall" || mimetype == "application/x-chrome-extension") {
                                    // Direct extension install from store Web pages
                                    viewModel.installExtensionFromUrl(url)
                                    viewModel.toggleMenu(BrowserViewModel.MenuType.EXTENSIONS)
                                } else {
                                    viewModel.startFileDownload(url, userAgent, contentDisposition, mimetype)
                                    viewModel.toggleMenu(BrowserViewModel.MenuType.DOWNLOADS)
                                }
                            }

                            // Block Ads via shouldInterceptRequest in WebViewClient
                            webViewClient = object : WebViewClient() {
                                
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return false // keep parsing in-app
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (!isAdBlockEnabled) {
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    val url = request?.url?.toString() ?: ""
                                    // Block common hostnames
                                    val adDomains = listOf(
                                        "doubleclick.net", "googleads", "adservice.google", "pagead",
                                        "adnxs", "taboola", "outbrain", "adcolony", "applovin",
                                        "flurry", "mopub", "scorecardresearch", "quantserve",
                                        "crwdcntrl", "adsystem", "pubmatic", "rubiconproject",
                                        "openx", "adroll", "criteo", "amazon-adsystem", "adform",
                                        "media.net", "ads-twitter", "ads.yahoo"
                                    )

                                    for (domain in adDomains) {
                                        if (url.contains(domain)) {
                                            // Intercept and return empty byte stream
                                            return WebResourceResponse(
                                                "text/plain",
                                                "UTF-8",
                                                ByteArrayInputStream("".toByteArray())
                                            )
                                        }
                                    }

                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    viewModel.updateLoadingState(true, 0)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    viewModel.updateLoadingState(false, 100)
                                    if (url != null) {
                                        viewModel.updateHistoryAndTitle(url, view?.title ?: "")
                                    }
                                    viewModel.setNavigationCapabilities(
                                        view?.canGoBack() == true,
                                        view?.canGoForward() == true
                                    )

                                    // Inject active extension script content
                                    coroutineScope.launch {
                                        val activeScripts = viewModel.enabledExtensions.value
                                        activeScripts.forEach { script ->
                                            view?.evaluateJavascript(script.scriptContent, null)
                                        }
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    viewModel.updateLoadingState(newProgress < 100, newProgress)
                                }
                            }

                            loadUrl(currentUrl)
                            webViewInstance = this
                        }
                    },
                    update = { webView: WebView ->
                        // If load trigger changes
                        if (webView.url != currentUrl) {
                            webView.loadUrl(currentUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 4. Overlaid Menu Drawers
        AnimatedVisibility(
            visible = activeMenu != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { viewModel.toggleMenu(null) }
                    ),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(420.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xEE1A1A20), Color(0xEE121215))
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF9800), Color(0xFF673AB7))
                            ),
                            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                        )
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                            clip = false,
                            ambientColor = Color(0xFFFF5722),
                            spotColor = Color(0xFF673AB7)
                        )
                        .padding(24.dp)
                        .clickable(enabled = false) {}, // Intercept clicks inside menu
                    contentAlignment = Alignment.TopStart
                ) {
                    when (activeMenu) {
                        BrowserViewModel.MenuType.BOOKMARKS -> BookmarksMenu(
                            bookmarks = bookmarks,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onBookmarkClick = {
                                viewModel.loadUrl(it.url)
                                viewModel.toggleMenu(null)
                            },
                            onDelete = { viewModel.deleteBookmark(it) }
                        )
                        BrowserViewModel.MenuType.HISTORY -> HistoryMenu(
                            history = historyEntries,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onHistoryClick = {
                                viewModel.loadUrl(it.url)
                                viewModel.toggleMenu(null)
                            },
                            onDelete = { viewModel.deleteHistory(it) },
                            onClearAll = { viewModel.clearAllHistory() }
                        )
                        BrowserViewModel.MenuType.DOWNLOADS -> DownloadsMenu(
                            downloads = downloads,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onCancel = { viewModel.cancelDownload(it) },
                            onOpen = { path ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.fromFile(File(path)), "*/*")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("TVBrowser", "No app to open downloaded file", e)
                                }
                            }
                        )
                        BrowserViewModel.MenuType.EXTENSIONS -> ExtensionsMenu(
                            extensions = extensions,
                            currentUrl = currentUrl,
                            installState = viewModel.installExtensionState.collectAsStateWithLifecycle().value,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onToggle = { viewModel.toggleExtension(it) },
                            onDelete = { viewModel.deleteExtension(it) },
                            onAddExtension = { name, desc, code ->
                                viewModel.addCustomExtension(name, desc, code)
                            },
                            onLoadUrl = { url ->
                                viewModel.loadUrl(url)
                                viewModel.toggleMenu(null)
                            },
                            onInstallFromUrl = { url -> viewModel.installExtensionFromUrl(url) },
                            viewModel = viewModel
                        )
                        BrowserViewModel.MenuType.CHROME_MENU -> ChromeMenu(
                            currentUrl = currentUrl,
                            canGoForward = canGoForward,
                            isBookmarked = bookmarks.any { it.url == currentUrl },
                            isDesktopActive = tabs.find { it.id == activeTabId }?.isDesktop == true,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onForward = {
                                webViewInstance?.goForward()
                                viewModel.toggleMenu(null)
                            },
                            onBookmarkToggle = {
                                viewModel.toggleBookmark(currentUrl, pageTitle)
                                viewModel.toggleMenu(null)
                            },
                            onDownloadTrigger = {
                                webViewInstance?.url?.let { viewModel.startFileDownload(it, null, null, null) }
                                viewModel.toggleMenu(null)
                            },
                            onRefresh = {
                                webViewInstance?.reload()
                                viewModel.toggleMenu(null)
                            },
                            onNewTab = {
                                viewModel.createNewTab()
                                viewModel.toggleMenu(null)
                            },
                            onNewIncognitoTab = {
                                viewModel.createNewTab(isIncognito = true)
                                viewModel.toggleMenu(null)
                            },
                            onAddTabGroup = {
                                val activeTab = tabs.find { it.id == activeTabId }
                                activeTab?.let { viewModel.addTabToNewGroup(it.id, "Group Alpha") }
                                android.widget.Toast.makeText(context, "Added active tab to \"Group Alpha\" group.", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.toggleMenu(null)
                            },
                            onHistory = { viewModel.toggleMenu(BrowserViewModel.MenuType.HISTORY) },
                            onDeleteData = {
                                viewModel.clearAllHistory()
                                webViewInstance?.clearCache(true)
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.widget.Toast.makeText(context, "Cleared all browsing data, caches, and session cookies.", android.widget.Toast.LENGTH_LONG).show()
                                viewModel.toggleMenu(null)
                            },
                            onDownloads = { viewModel.toggleMenu(BrowserViewModel.MenuType.DOWNLOADS) },
                            onBookmarks = { viewModel.toggleMenu(BrowserViewModel.MenuType.BOOKMARKS) },
                            onRecentTabs = { viewModel.toggleMenu(BrowserViewModel.MenuType.RECENT_TABS) },
                            onShare = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share link"))
                                viewModel.toggleMenu(null)
                            },
                            onFindInPage = {
                                viewModel.setFindInPageActive(true)
                                viewModel.toggleMenu(null)
                            },
                            onTranslate = {
                                val encoded = android.net.Uri.encode(currentUrl)
                                viewModel.loadUrl("https://translate.google.com/translate?sl=auto&tl=en&u=$encoded")
                                viewModel.toggleMenu(null)
                            },
                            onReadingMode = {
                                webViewInstance?.evaluateJavascript(
                                    "(function() { " +
                                    "  var text = ''; " +
                                    "  var el = document.querySelectorAll('p, h1, h2, h3'); " +
                                    "  for (var i = 0; i < el.length; i++) { " +
                                    "    text += el[i].innerText + '\\n\\n'; " +
                                    "  } " +
                                    "  return text || document.body.innerText; " +
                                    "})()"
                                ) { res ->
                                    val decoded = res?.trim('"')
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                    viewModel.toggleReadingMode(true, decoded ?: "")
                                }
                                viewModel.toggleMenu(null)
                            },
                            onAddToHome = {
                                android.widget.Toast.makeText(context, "Added shortcut for \"$pageTitle\" to home screen feed.", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.toggleMenu(null)
                            },
                            onDesktopToggle = {
                                viewModel.toggleDesktopSiteForActiveTab()
                                viewModel.toggleMenu(null)
                            },
                            onSettings = { viewModel.toggleMenu(BrowserViewModel.MenuType.SETTINGS_PANEL) },
                            onHelpFeedback = { viewModel.toggleMenu(BrowserViewModel.MenuType.HELP_FEEDBACK) }
                        )
                        BrowserViewModel.MenuType.SETTINGS_PANEL -> SettingsPanel(
                            currentEngine = defaultSearchEngine,
                            currentZoom = zoomLevel,
                            adBlockActive = isAdBlockEnabled,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onEngineSelect = { viewModel.setDefaultSearchEngine(it) },
                            onZoomSelect = {
                                viewModel.setZoomLevel(it)
                                webViewInstance?.settings?.textZoom = it
                            },
                            onAdBlockToggle = { viewModel.toggleAdBlock() },
                            onBackToMenu = { viewModel.toggleMenu(BrowserViewModel.MenuType.CHROME_MENU) }
                        )
                        BrowserViewModel.MenuType.RECENT_TABS -> RecentTabsPanel(
                            closedTabs = recentlyClosedTabs,
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onTabClick = { url ->
                                viewModel.loadUrl(url)
                                viewModel.toggleMenu(null)
                            },
                            onBackToMenu = { viewModel.toggleMenu(BrowserViewModel.MenuType.CHROME_MENU) }
                        )
                        BrowserViewModel.MenuType.HELP_FEEDBACK -> HelpFeedbackPanel(
                            cursorX = cursorX,
                            cursorY = cursorY,
                            density = density.density,
                            onBackToMenu = { viewModel.toggleMenu(BrowserViewModel.MenuType.CHROME_MENU) }
                        )
                        else -> {}
                    }
                }
            }
        }

        // 4b. Reading Mode Overlay
        if (isReadingModeActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF151518))
                    .padding(28.dp)
                    .clickable {}, // Consume clicks
                contentAlignment = Alignment.TopStart
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📖 Reader View",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                        IconButton(
                            onClick = { viewModel.toggleReadingMode(false) },
                            modifier = Modifier.background(Color(0xFF333333), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit Reader View",
                                tint = Color.White
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                text = pageTitle,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Source: $currentUrl",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                        }
                        
                        item {
                            val content = readingContent.ifEmpty { "Extracting content from page... Please wait." }
                            Text(
                                text = content,
                                fontSize = 16.sp,
                                color = Color(0xFFE2E2E5),
                                lineHeight = 26.sp,
                                modifier = Modifier.padding(bottom = 40.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Virtual Mouse Cursor icon overlaid dynamically matching coordinates at outer level
        if (cursorMode) {
            val cursorXOffset by animateDpAsState(targetValue = cursorX.dp, label = "cursorX")
            val cursorYOffset by animateDpAsState(targetValue = cursorY.dp, label = "cursorY")

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            cursorXOffset.roundToPx(),
                            cursorYOffset.roundToPx()
                        )
                    }
                    .size(40.dp)
            ) {
                // Radiant outer cosmic glowing aura that breathes
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.Center)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF9800).copy(alpha = 0.45f),
                                    Color(0xFFCE93D8).copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                
                // Futuristic neon glowing pointer
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Virtual Cursor",
                    tint = Color(0xFFFF5722),
                    modifier = Modifier
                        .rotate(315f)
                        .size(26.dp)
                        .align(Alignment.Center)
                        .shadow(
                            elevation = 12.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFFFF5722),
                            spotColor = Color(0xFFFF9800)
                        )
                )
            }
        }
    }
}

// Top Bar Action Navigation Bar
@Composable
fun NavigationBarRow(
    addressValue: String,
    onAddressChanged: (String) -> Unit,
    onSearchTriggered: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    cursorEnabled: Boolean,
    onCursorModeToggled: () -> Unit,
    adBlockActive: Boolean,
    onAdBlockToggle: () -> Unit,
    onMenuSelect: (BrowserViewModel.MenuType) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    onVoiceTrigger: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        color = Color(0xFF1E1E22),
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Back button
            NavigationIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Go Back",
                enabled = canGoBack,
                onClick = onBack,
                cursorEnabled = cursorEnabled
            )

            // Forward button
            NavigationIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Go Forward",
                enabled = canGoForward,
                onClick = onForward,
                cursorEnabled = cursorEnabled
            )

            // Reload / Stop loading button
            NavigationIconButton(
                icon = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                contentDescription = "Reload",
                enabled = true,
                onClick = onReload,
                cursorEnabled = cursorEnabled
            )

            // Dynamic Search Address Bar Textfield
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF2B2B30))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Web Search Icon",
                        tint = Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )

                    BasicTextField(
                        value = addressValue,
                        onValueChange = onAddressChanged,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(Color(0xFFFF9800)),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { onSearchTriggered() }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { canFocus = !cursorEnabled }
                    )

                    // Voice assistant search navigator
                    IconButton(
                        onClick = onVoiceTrigger,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF3E3E44), CircleShape)
                            .focusProperties { canFocus = !cursorEnabled }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice search",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Quick Bookmark star
            NavigationIconButton(
                icon = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Bookmark present url",
                enabled = true,
                onClick = onBookmarkToggle,
                tint = if (isBookmarked) Color(0xFFFFC107) else Color.DarkGray,
                cursorEnabled = cursorEnabled
            )

            // Virtual mouse cursor toggle controller
            NavigationIconButton(
                icon = Icons.Default.Mouse,
                contentDescription = "Mouse cursor mode toggle",
                enabled = true,
                onClick = onCursorModeToggled,
                activeHighlight = cursorEnabled,
                tint = if (cursorEnabled) Color(0xFFFF5722) else Color.DarkGray,
                cursorEnabled = cursorEnabled
            )

            // Ad blocker toggler
            NavigationIconButton(
                icon = if (adBlockActive) Icons.Default.Security else Icons.Default.SecurityUpdateWarning,
                contentDescription = "Toggle Ad Block",
                enabled = true,
                onClick = onAdBlockToggle,
                activeHighlight = adBlockActive,
                tint = if (adBlockActive) Color(0xFF00E676) else Color.DarkGray,
                cursorEnabled = cursorEnabled
            )

            // Bookmarks Menu trigger
            NavigationIconButton(
                icon = Icons.Default.Bookmarks,
                contentDescription = "Open Bookmarks drawer",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.BOOKMARKS) },
                cursorEnabled = cursorEnabled
            )

            // History menu trigger
            NavigationIconButton(
                icon = Icons.Default.History,
                contentDescription = "Open History drawer",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.HISTORY) },
                cursorEnabled = cursorEnabled
            )

            // Extensions Settings list trigger
            NavigationIconButton(
                icon = Icons.Default.Extension,
                contentDescription = "Extension Manager Settings",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.EXTENSIONS) },
                cursorEnabled = cursorEnabled
            )

            // Downloads trigger
            NavigationIconButton(
                icon = Icons.Default.Download,
                contentDescription = "Downloads list Panel",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.DOWNLOADS) },
                cursorEnabled = cursorEnabled
            )

            // Chrome More Options Overflow Menu
            NavigationIconButton(
                icon = Icons.Default.MoreVert,
                contentDescription = "Chrome Menu Options",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.CHROME_MENU) },
                cursorEnabled = cursorEnabled
            )
        }
    }
}

// Icon Button with Custom Focus Glows
@Composable
fun NavigationIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    activeHighlight: Boolean = false,
    tint: Color = Color.White,
    cursorEnabled: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderGlow = if (isFocused) {
        Modifier.border(2.dp, Color(0xFFFF9800), CircleShape)
    } else if (activeHighlight) {
        Modifier.border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
    } else Modifier

    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .focusProperties { canFocus = !cursorEnabled }
            .background(
                if (isFocused) Color(0xFF3D3D44) 
                else if (activeHighlight) tint.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .then(borderGlow)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Color.Gray,
            modifier = Modifier.size(20.dp)
        )
    }
}

// Bookmarks Panel View Drawer
@Composable
fun BookmarksMenu(
    bookmarks: List<Bookmark>,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onBookmarkClick: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Bookmarks",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No Bookmarks Saved Yet",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkHistoryItem(
                        title = bookmark.title,
                        url = bookmark.url,
                        cursorX = cursorX,
                        cursorY = cursorY,
                        density = density,
                        onClick = { onBookmarkClick(bookmark) },
                        onDelete = { onDelete(bookmark) }
                    )
                }
            }
        }
    }
}

// History List Drawer Panel
@Composable
fun HistoryMenu(
    history: List<HistoryEntry>,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onHistoryClick: (HistoryEntry) -> Unit,
    onDelete: (id: Long) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History logs",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (history.isNotEmpty()) {
                Box(modifier = Modifier.cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))) {
                    Button(
                        onClick = onClearAll,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Clear All", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Browsing history is empty",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { entry ->
                    BookmarkHistoryItem(
                        title = entry.title,
                        url = entry.url,
                        cursorX = cursorX,
                        cursorY = cursorY,
                        density = density,
                        onClick = { onHistoryClick(entry) },
                        onDelete = { onDelete(entry.id) }
                    )
                }
            }
        }
    }
}

// Row item layout reusable for Bookmarks & History lists
@Composable
fun BookmarkHistoryItem(
    title: String,
    url: String,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF323236) else Color(0xFF222225)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isFocused) Color(0xFFFF9800) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifEmpty { "Web Page" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = url,
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAF),
                    maxLines = 1
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete entry",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Downloads view screen
@Composable
fun DownloadsMenu(
    downloads: List<DownloadItem>,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onCancel: (Long) -> Unit,
    onOpen: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Downloads Manager",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No Downloads Found",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads) { download ->
                    DownloadCardItem(download, cursorX, cursorY, density, onCancel, onOpen)
                }
            }
        }
    }
}

@Composable
fun DownloadCardItem(
    item: DownloadItem,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onCancel: (Long) -> Unit,
    onOpen: (String) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF323236) else Color(0xFF222225)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isFocused) Color(0xFFFF9800) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = item.fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Status: ${item.status}",
                fontSize = 11.sp,
                color = when (item.status) {
                    "COMPLETED" -> Color(0xFF4CAF50)
                    "DOWNLOADING" -> Color(0xFFFFC107)
                    else -> Color(0xFFF44336)
                }
            )

            if (item.status == "DOWNLOADING") {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                        color = Color(0xFFFF9800),
                        trackColor = Color(0xFF4A4A4A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${item.progress}%",
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.align(Alignment.End).cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(6.dp))) {
                    Button(
                        onClick = { onCancel(item.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Cancel", fontSize = 10.sp, color = Color.White)
                    }
                }
            } else if (item.status == "COMPLETED") {
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.align(Alignment.End).cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(6.dp))) {
                    Button(
                        onClick = { onOpen(item.filePath) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Open", fontSize = 10.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// Extensions settings menu with direct Firefox web store installer integrations
@Composable
fun ExtensionsMenu(
    extensions: List<ExtensionScript>,
    currentUrl: String,
    installState: InstallState,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onToggle: (ExtensionScript) -> Unit,
    onDelete: (ExtensionScript) -> Unit,
    onAddExtension: (String, String, String) -> Unit,
    onLoadUrl: (String) -> Unit,
    onInstallFromUrl: (String) -> Unit,
    viewModel: BrowserViewModel
) {
    var showAddPane by remember { mutableStateOf(false) }
    var extName by remember { mutableStateOf("") }
    var extDesc by remember { mutableStateOf("") }
    var extCode by remember { mutableStateOf("") }

    var pastePackageUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Extensions Manager",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Button(
                onClick = { showAddPane = !showAddPane },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showAddPane) Color.DarkGray else Color(0xFFFF9800)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (showAddPane) "View List" else "+ Custom Script", fontSize = 11.sp, color = Color.White)
            }
        }

        // Live Installation Status Alerts
        if (installState !is InstallState.Idle) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (installState) {
                        is InstallState.Installing -> Color(0xFF1E3A8A)
                        is InstallState.Success -> Color(0xFF064E3B)
                        is InstallState.Error -> Color(0xFF7F1D1D)
                        else -> Color.Transparent
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = when (installState) {
                            is InstallState.Installing -> "INSTALLING EXTENSION"
                            is InstallState.Success -> "INSTALL SUCCESSFUL"
                            is InstallState.Error -> "INSTALLATION ERROR"
                            else -> ""
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (installState) {
                            is InstallState.Installing -> Color(0xFF60A5FA)
                            is InstallState.Success -> Color(0xFF34D399)
                            is InstallState.Error -> Color(0xFFF87171)
                            else -> Color.White
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (installState) {
                            is InstallState.Installing -> installState.message
                            is InstallState.Success -> installState.message
                            is InstallState.Error -> installState.message
                            else -> ""
                        },
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    if (installState is InstallState.Installing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = Color(0xFFFF9800),
                            trackColor = Color(0xFFA1A1AA).copy(alpha = 0.3f)
                        )
                    }
                    if (installState !is InstallState.Installing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.clearInstallState() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(26.dp).align(Alignment.End)
                        ) {
                            Text("Dismiss", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        if (showAddPane) {
            // Screen overlay block to input user-defined Chrome/Firefox Extension Script
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Inject Custom Extension JS Script", color = Color(0xFFFF9800), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = extName,
                        onValueChange = { extName = it },
                        label = { Text("Extension Name") },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extDesc,
                        onValueChange = { extDesc = it },
                        label = { Text("Short Description") },
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extCode,
                        onValueChange = { extCode = it },
                        label = { Text("Javascript Content-Script Code") },
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (extName.isNotEmpty() && extCode.isNotEmpty()) {
                                onAddExtension(extName, extDesc, extCode)
                                extName = ""
                                extDesc = ""
                                extCode = ""
                                showAddPane = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add and Inject Script", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Feature 1: Firefox AMO Directory Portal Card
                item {
                    Text(
                        text = "EXPLORE FIREFOX ADD-ONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        onClick = { onLoadUrl("https://addons.mozilla.org/en-US/firefox/") },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF232529)),
                        modifier = Modifier.fillMaxWidth().height(66.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Language, "AMO", tint = Color(0xFFFF5722), modifier = Modifier.size(20.dp))
                                Text("Go to Firefox Add-ons Website", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Feature 2: Quick Direct Store Installers (Manual Copy/Paste)
                item {
                    Text(
                        text = "PASTE LINK & INSTALL DIRECTLY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Firefox AMO / External Link installer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF232529))
                            .padding(10.dp)
                    ) {
                        Text("Install Firefox (.xpi) or direct addon link:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = pastePackageUrl,
                                onValueChange = { pastePackageUrl = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                cursorBrush = SolidColor(Color(0xFFFF9800)),
                                modifier = Modifier
                                    .weight(1f)
                                    .background(Color(0xFF141518), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            )
                            Button(
                                onClick = {
                                    val trimmedUrl = pastePackageUrl.trim()
                                    if (trimmedUrl.isNotEmpty()) {
                                        onInstallFromUrl(trimmedUrl)
                                        pastePackageUrl = ""
                                    }
                                },
                                enabled = pastePackageUrl.trim().isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Load", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Feature 4: Loaded Extensions List
                item {
                    Text(
                        text = "INSTALLED EXTENSIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                }

                if (extensions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No direct web store extensions currently loaded",
                                color = Color.DarkGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(extensions) { ext ->
                        ExtensionCardItem(ext, cursorX, cursorY, density, onToggle, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
fun ExtensionCardItem(
    item: ExtensionScript,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onToggle: (ExtensionScript) -> Unit,
    onDelete: (ExtensionScript) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) Color(0xFF323236) else Color(0xFF222225)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isFocused) Color(0xFFFF9800) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Category: ${item.category}",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                }

                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = { onToggle(item) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFFF9800),
                        checkedTrackColor = Color(0xFFFF9800).copy(alpha = 0.5f)
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.description,
                fontSize = 11.sp,
                color = Color(0xFFAAAAAF)
            )

            if (item.isUserAdded) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.align(Alignment.End).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                    IconButton(
                        onClick = { onDelete(item) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Uninstall",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChromeMenu(
    currentUrl: String,
    canGoForward: Boolean,
    isBookmarked: Boolean,
    isDesktopActive: Boolean,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onForward: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onDownloadTrigger: () -> Unit,
    onRefresh: () -> Unit,
    onNewTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onAddTabGroup: () -> Unit,
    onHistory: () -> Unit,
    onDeleteData: () -> Unit,
    onDownloads: () -> Unit,
    onBookmarks: () -> Unit,
    onRecentTabs: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onTranslate: () -> Unit,
    onReadingMode: () -> Unit,
    onAddToHome: () -> Unit,
    onDesktopToggle: () -> Unit,
    onSettings: () -> Unit,
    onHelpFeedback: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Quick Actions Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF2E2E33), Color(0xFF232326))
                    ),
                    RoundedCornerShape(12.dp)
                )
                .border(0.5.dp, Color(0xFF424248), RoundedCornerShape(12.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.padding(2.dp).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go Forward",
                        tint = if (canGoForward) Color.White else Color.Gray
                    )
                }
            }
            Box(modifier = Modifier.padding(2.dp).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onBookmarkToggle) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Bookmark Page",
                        tint = if (isBookmarked) Color(0xFFFFD54F) else Color.White
                    )
                }
            }
            Box(modifier = Modifier.padding(2.dp).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onDownloadTrigger) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Download Link",
                        tint = Color.White
                    )
                }
            }
            Box(modifier = Modifier.padding(2.dp).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = { /* Display secure protocol badge info */ }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Page Info",
                        tint = Color.White
                    )
                }
            }
            Box(modifier = Modifier.padding(2.dp).cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload Page",
                        tint = Color.White
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF323236), thickness = 1.dp, modifier = Modifier.padding(bottom = 12.dp))

        // Actions List
        ChromeMenuItem(icon = Icons.Default.AddBox, label = "New tab", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onNewTab)
        ChromeMenuItem(icon = Icons.Default.VisibilityOff, label = "New Incognito tab", labelColor = Color(0xFFFFB74D), cursorX = cursorX, cursorY = cursorY, density = density, onClick = onNewIncognitoTab)
        ChromeMenuItem(icon = Icons.Default.DynamicFeed, label = "Add tab to new group", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onAddTabGroup)
        
        HorizontalDivider(color = Color(0xFF2C2C2F), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        
        ChromeMenuItem(icon = Icons.Default.History, label = "History", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onHistory)
        ChromeMenuItem(icon = Icons.Default.DeleteForever, label = "Clear browsing data", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onDeleteData)
        ChromeMenuItem(icon = Icons.Default.Download, label = "Downloads", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onDownloads)
        ChromeMenuItem(icon = Icons.Default.Bookmarks, label = "Bookmarks", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onBookmarks)
        ChromeMenuItem(icon = Icons.Default.Restore, label = "Recent tabs", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onRecentTabs)

        HorizontalDivider(color = Color(0xFF2C2C2F), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        ChromeMenuItem(icon = Icons.Default.Share, label = "Share...", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onShare)
        ChromeMenuItem(icon = Icons.Default.FindInPage, label = "Find in page", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onFindInPage)
        ChromeMenuItem(icon = Icons.Default.Translate, label = "Translate...", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onTranslate)
        ChromeMenuItem(icon = Icons.Default.MenuBook, label = "Show Reading mode", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onReadingMode)
        ChromeMenuItem(icon = Icons.Default.AddToHomeScreen, label = "Add to Home screen", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onAddToHome)
        
        // Desktop version toggle with checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDesktopToggle() }
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Laptop,
                    contentDescription = "Desktop site option",
                    tint = Color.LightGray,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Desktop site",
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
            Checkbox(
                checked = isDesktopActive,
                onCheckedChange = { onDesktopToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFFFF9800),
                    uncheckedColor = Color.LightGray
                )
            )
        }

        HorizontalDivider(color = Color(0xFF2C2C2F), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        ChromeMenuItem(icon = Icons.Default.Settings, label = "Settings", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onSettings)
        ChromeMenuItem(icon = Icons.Default.Help, label = "Help & feedback", cursorX = cursorX, cursorY = cursorY, density = density, onClick = onHelpFeedback)
    }
}

@Composable
fun ChromeMenuItem(
    icon: ImageVector,
    label: String,
    labelColor: Color = Color.White,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (labelColor == Color.White) Color.LightGray else labelColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = labelColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SettingsPanel(
    currentEngine: String,
    currentZoom: Int,
    adBlockActive: Boolean,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onEngineSelect: (String) -> Unit,
    onZoomSelect: (Int) -> Unit,
    onAdBlockToggle: () -> Unit,
    onBackToMenu: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onBackToMenu) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to menu",
                        tint = Color.White
                    )
                }
            }
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = "Basics",
                    color = Color(0xFFFF9800),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Search engine",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf("Google" to "https://www.google.com/search?q=", "Bing" to "https://www.bing.com/search?q=", "DuckDuckGo" to "https://duckduckgo.com/?q=", "Yahoo" to "https://search.yahoo.com/search?p=").forEach { (name, url) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEngineSelect(url) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = name, color = Color.LightGray, fontSize = 14.sp)
                            RadioButton(
                                selected = currentEngine == url,
                                onClick = { onEngineSelect(url) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF9800))
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider(color = Color(0xFF323236)) }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Page zoom (" + currentZoom + "%)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(75, 100, 125, 150, 175, 200).forEach { zoom ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onZoomSelect(zoom) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "$zoom%", color = Color.LightGray, fontSize = 14.sp)
                            RadioButton(
                                selected = currentZoom == zoom,
                                onClick = { onZoomSelect(zoom) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF9800))
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider(color = Color(0xFF323236)) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAdBlockToggle() }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "Ad Shield", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Block popups & commercial banners", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = adBlockActive,
                        onCheckedChange = { onAdBlockToggle() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800))
                    )
                }
            }
        }
    }
}

@Composable
fun RecentTabsPanel(
    closedTabs: List<Pair<String, String>>,
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onTabClick: (String) -> Unit,
    onBackToMenu: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onBackToMenu) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to menu",
                        tint = Color.White
                    )
                }
            }
            Text(
                text = "Recently closed",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (closedTabs.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "None found in history session.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(closedTabs) { (url, title) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTabClick(url) }
                            .padding(10.dp)
                    ) {
                        Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = url, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun HelpFeedbackPanel(
    cursorX: Float = 0f,
    cursorY: Float = 0f,
    density: Float = 1f,
    onBackToMenu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.cursorHoverEffect(cursorX, cursorY, density, CircleShape)) {
                IconButton(onClick = onBackToMenu) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to menu",
                        tint = Color.White
                    )
                }
            }
            Text(
                text = "Help & feedback",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Text(
            text = "Frequently Asked Questions",
            color = Color(0xFFFF9800),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
        )

        val faqs = listOf(
            "How do I use Multi-tab browsing?" to "Tap any open tab in the dark tab bar right below the main search bar, or tap the '+' icon to launch a brand new independent session.",
            "Can I write or search using voice?" to "Yes, hit the microphone icon in the main top row to trigger the standard prompt dialog.",
            "What is Web ad shield?" to "It blocks heavy data networks, doubleclick overlays, and tracking scripts dynamically in-line.",
            "How does Reader mode work?" to "Select 'Show Reading mode' from the Chrome overflow menu. It isolates text elements instantly for high readability distraction-free."
        )

        faqs.forEach { (q, a) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .cursorHoverEffect(cursorX, cursorY, density, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(text = "Q: $q", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "A: $a", color = Color.LightGray, fontSize = 13.sp)
            }
            HorizontalDivider(color = Color(0xFF2C2C2F), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Browser Build Info: Version 120.0-ChromeMobileTV-Ready",
            color = Color.Gray,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun Modifier.cursorHoverEffect(
    cursorX: Float,
    cursorY: Float,
    density: Float,
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier {
    var isHovered by remember { mutableStateOf(false) }
    return this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val position = coords.positionInRoot()
                val size = coords.size
                val cx = cursorX * density
                val cy = cursorY * density
                val hover = cx >= position.x && cx <= (position.x + size.width) &&
                            cy >= position.y && cy <= (position.y + size.height)
                if (hover != isHovered) {
                    isHovered = hover
                }
            }
        }
        .drawBehind {
            if (isHovered) {
                val bgBrush = Brush.linearGradient(
                    colors = listOf(Color(0x35FF9800), Color(0x22CE93D8))
                )
                val radius = if (shape == CircleShape) {
                    size.minDimension / 2f
                } else {
                    10.dp.toPx()
                }
                
                // Draw background
                drawRoundRect(
                    brush = bgBrush,
                    cornerRadius = CornerRadius(radius, radius)
                )
                
                // Draw border
                val borderBrush = Brush.linearGradient(
                    colors = listOf(Color(0xFFFF9800), Color(0xFFCE93D8))
                )
                drawRoundRect(
                    brush = borderBrush,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
}
