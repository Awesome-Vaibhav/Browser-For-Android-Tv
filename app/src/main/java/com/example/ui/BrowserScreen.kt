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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var addressInput by remember { mutableStateOf(currentUrl) }

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
            .onKeyEvent { keyEvent ->
                if (activeMenu != null) return@onKeyEvent false

                val isKeyDown = keyEvent.type == KeyEventType.KeyDown
                if (isKeyDown) {
                    val stepMultiplier = if (keyEvent.nativeKeyEvent.metaState and android.view.KeyEvent.META_SHIFT_ON != 0) 3.5f else 1f
                    val step = 16f * stepMultiplier

                    when (keyEvent.key) {
                        Key.DirectionUp -> {
                            if (cursorMode) {
                                // If cursor near top border, scroll webview slightly up
                                if (cursorY < 120f) {
                                    webViewInstance?.scrollBy(0, -150)
                                }
                                viewModel.moveCursor(0f, -step)
                                true
                            } else false
                        }
                        Key.DirectionDown -> {
                            if (cursorMode) {
                                // If cursor near bottom border, scroll webview slightly down
                                if (cursorY > 600f) {
                                    webViewInstance?.scrollBy(0, 150)
                                }
                                viewModel.moveCursor(0f, step)
                                true
                            } else false
                        }
                        Key.DirectionLeft -> {
                            if (cursorMode) {
                                viewModel.moveCursor(-step, 0f)
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (cursorMode) {
                                viewModel.moveCursor(step, 0f)
                                true
                            } else false
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (cursorMode) {
                                simulateWebViewTouch(cursorX, cursorY)
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
                                viewModel.startFileDownload(url, userAgent, contentDisposition, mimetype)
                                viewModel.toggleMenu(BrowserViewModel.MenuType.DOWNLOADS)
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

                // 3. Virtual Mouse Cursor icon overlaid dynamically matching coordinates
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
                            .size(28.dp)
                    ) {
                        // Styled highly-visible arrow pointer
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Virtual Cursor",
                            tint = Color(0xFFFF5722),
                            modifier = Modifier
                                .rotate(315f)
                                .size(28.dp)
                                .shadow(8.dp, CircleShape)
                        )
                    }
                }
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
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF212124), Color(0xFF1B1B1D))
                            )
                        )
                        .border(1.dp, Color(0xFF323236), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                        .padding(24.dp)
                        .clickable(enabled = false) {}, // Intercept clicks inside menu
                    contentAlignment = Alignment.TopStart
                ) {
                    when (activeMenu) {
                        BrowserViewModel.MenuType.BOOKMARKS -> BookmarksMenu(
                            bookmarks = bookmarks,
                            onBookmarkClick = {
                                viewModel.loadUrl(it.url)
                                viewModel.toggleMenu(null)
                            },
                            onDelete = { viewModel.deleteBookmark(it) }
                        )
                        BrowserViewModel.MenuType.HISTORY -> HistoryMenu(
                            history = historyEntries,
                            onHistoryClick = {
                                viewModel.loadUrl(it.url)
                                viewModel.toggleMenu(null)
                            },
                            onDelete = { viewModel.deleteHistory(it) },
                            onClearAll = { viewModel.clearAllHistory() }
                        )
                        BrowserViewModel.MenuType.DOWNLOADS -> DownloadsMenu(
                            downloads = downloads,
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
                            onToggle = { viewModel.toggleExtension(it) },
                            onDelete = { viewModel.deleteExtension(it) },
                            onAddExtension = { name, desc, code ->
                                viewModel.addCustomExtension(name, desc, code)
                            }
                        )
                        else -> {}
                    }
                }
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
                onClick = onBack
            )

            // Forward button
            NavigationIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Go Forward",
                enabled = canGoForward,
                onClick = onForward
            )

            // Reload / Stop loading button
            NavigationIconButton(
                icon = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                contentDescription = "Reload",
                enabled = true,
                onClick = onReload
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
                        modifier = Modifier.weight(1f)
                    )

                    // Voice assistant search navigator
                    IconButton(
                        onClick = onVoiceTrigger,
                        modifier = Modifier.size(36.dp).background(Color(0xFF3E3E44), CircleShape)
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
                tint = if (isBookmarked) Color(0xFFFFC107) else Color.DarkGray
            )

            // Virtual mouse cursor toggle controller
            NavigationIconButton(
                icon = Icons.Default.Mouse,
                contentDescription = "Mouse cursor mode toggle",
                enabled = true,
                onClick = onCursorModeToggled,
                activeHighlight = cursorEnabled,
                tint = if (cursorEnabled) Color(0xFFFF5722) else Color.DarkGray
            )

            // Ad blocker toggler
            NavigationIconButton(
                icon = if (adBlockActive) Icons.Default.Security else Icons.Default.SecurityUpdateWarning,
                contentDescription = "Toggle Ad Block",
                enabled = true,
                onClick = onAdBlockToggle,
                activeHighlight = adBlockActive,
                tint = if (adBlockActive) Color(0xFF00E676) else Color.DarkGray
            )

            // Bookmarks Menu trigger
            NavigationIconButton(
                icon = Icons.Default.Bookmarks,
                contentDescription = "Open Bookmarks drawer",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.BOOKMARKS) }
            )

            // History menu trigger
            NavigationIconButton(
                icon = Icons.Default.History,
                contentDescription = "Open History drawer",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.HISTORY) }
            )

            // Extensions Settings list trigger
            NavigationIconButton(
                icon = Icons.Default.Extension,
                contentDescription = "Extension Manager Settings",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.EXTENSIONS) }
            )

            // Downloads trigger
            NavigationIconButton(
                icon = Icons.Default.Download,
                contentDescription = "Downloads list Panel",
                enabled = true,
                onClick = { onMenuSelect(BrowserViewModel.MenuType.DOWNLOADS) }
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
    tint: Color = Color.White
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
                    DownloadCardItem(download, onCancel, onOpen)
                }
            }
        }
    }
}

@Composable
fun DownloadCardItem(
    item: DownloadItem,
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
                Button(
                    onClick = { onCancel(item.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp).align(Alignment.End)
                ) {
                    Text("Cancel", fontSize = 10.sp, color = Color.White)
                }
            } else if (item.status == "COMPLETED") {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onOpen(item.filePath) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp).align(Alignment.End)
                ) {
                    Text("Open", fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

// Extensions settings menu
@Composable
fun ExtensionsMenu(
    extensions: List<ExtensionScript>,
    onToggle: (ExtensionScript) -> Unit,
    onDelete: (ExtensionScript) -> Unit,
    onAddExtension: (String, String, String) -> Unit
) {
    var showAddPane by remember { mutableStateOf(false) }
    var extName by remember { mutableStateOf("") }
    var extDesc by remember { mutableStateOf("") }
    var extCode by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
                Text(if (showAddPane) "View Active" else "+ Custom Script", fontSize = 11.sp, color = Color.White)
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
            if (extensions.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No extension scripts pre-loaded",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(extensions) { ext ->
                        ExtensionCardItem(ext, onToggle, onDelete)
                    }
                }
            }
        }
    }
}

@Composable
fun ExtensionCardItem(
    item: ExtensionScript,
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
                IconButton(
                    onClick = { onDelete(item) },
                    modifier = Modifier.size(28.dp).align(Alignment.End)
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
