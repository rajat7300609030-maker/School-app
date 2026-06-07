package com.example

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class MainActivity : ComponentActivity() {

    private var mInterstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    // File chooser callback state for web uploads
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == android.app.Activity.RESULT_OK) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData
            if (clipData != null) {
                val count = clipData.itemCount
                val uris = Array(count) { i -> clipData.getItemAt(i).uri }
                uris
            } else if (dataString != null) {
                arrayOf(Uri.parse(dataString))
            } else {
                null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private fun requestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        permissionsToRequest.add(android.Manifest.permission.CAMERA)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }

    inner class WebShareInterface {
        @android.webkit.JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val body = buildString {
                    if (!title.isNullOrEmpty()) append("$title\n")
                    if (!text.isNullOrEmpty()) append("$text\n")
                    if (!url.isNullOrEmpty()) append(url)
                }
                putExtra(Intent.EXTRA_TEXT, body)
                if (!title.isNullOrEmpty()) {
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }
            }
            startActivity(Intent.createChooser(shareIntent, "Share learning resource"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestAppPermissions()

        // Initialize Google Mobile Ads SDK
        MobileAds.initialize(this) {
            // Mobile Ads Initialized, pre-load our first interstitial
            loadInterstitialAd()
        }

        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
    }

    // Helper to load Interstitial Ad from Env or Fallback to Test ID
    private fun loadInterstitialAd() {
        if (isAdLoading || mInterstitialAd != null) return
        isAdLoading = true

        val adUnitId = if (BuildConfig.ADMOB_INTERSTITIAL_AD_ID.isNotEmpty()) {
            BuildConfig.ADMOB_INTERSTITIAL_AD_ID
        } else {
            "ca-app-pub-3940256099942544/1033173712" // Official AdMob Test Interstitial ID
        }

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(this, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                mInterstitialAd = null
                isAdLoading = false
                // Attempt to retry after a short delay (e.g., 10 seconds)
            }

            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                mInterstitialAd = interstitialAd
                isAdLoading = false

                mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        mInterstitialAd = null
                        // Load next ad to handle subsequent requests
                        loadInterstitialAd()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        mInterstitialAd = null
                        loadInterstitialAd()
                    }
                }
            }
        })
    }

    // Trigger showing the Interstitial ad
    fun showInterstitial(onAdDismissedOrUnavailable: () -> Unit) {
        val ad = mInterstitialAd
        if (ad != null) {
            ad.show(this)
        } else {
            // Ad wasn't ready yet, notify and reload
            onAdDismissedOrUnavailable()
            loadInterstitialAd()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MainLayout() {
        val targetUrl = "https://theeducationhills.netlify.app/"
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        
        // Navigation States
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var loadProgress by remember { mutableFloatStateOf(0f) }
        var isPageLoading by remember { mutableStateOf(true) }
        
        // Error States
        var hasError by remember { mutableStateOf(false) }
        var errorDescription by remember { mutableStateOf("") }

        // Exit confirmation dialog state
        var showExitDialog by remember { mutableStateOf(false) }

        // Back Pressed Custom Rule: Handles WebView back history, active exit confirmation, or displaying confirmation dialog.
        BackHandler(enabled = true) {
            if (showExitDialog) {
                showExitDialog = false
            } else if (canGoBack && !hasError) {
                webViewRef?.let {
                    if (it.canGoBack()) {
                        it.goBack()
                    } else {
                        showExitDialog = true
                    }
                } ?: run {
                    showExitDialog = true
                }
            } else {
                showExitDialog = true
            }
        }

        // Automatic Interstitial Ad Trigger every 40 seconds
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(40000L) // 40 seconds
                showInterstitial {
                    // Ad was not ready yet, it will try again in 40 seconds
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                // Banner Ad wrapped elegantly in a bottom bar container with top border divider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        thickness = 1.dp
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            AdmobBannerAdBlock(modifier = Modifier.testTag("admob_banner"))
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Slim loader bar overlay at the absolute top of web content area
                AnimatedVisibility(
                    visible = isPageLoading && !hasError,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                }

                if (hasError) {
                    // Custom Gorgeous Error Scene
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Offline",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(72.dp)
                                .padding(bottom = 16.dp)
                        )
                        Text(
                            text = "Connection Refused or Offline",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (errorDescription.isNotEmpty()) errorDescription else "Please check your network signal or try reloading.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                hasError = false
                                isPageLoading = true
                                webViewRef?.reload()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Connection")
                        }
                    }
                }

                // WebView Container, rendered whenever we do not have a full-screen network block error
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("webview_core"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isPageLoading = true
                                    hasError = false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isPageLoading = false
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true

                                    // Inject bridge for window.navigator.share
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            if (!navigator.share || window.AndroidShare) {
                                                navigator.share = function(data) {
                                                    if (window.AndroidShare) {
                                                        window.AndroidShare.share(data.title || "", data.text || "", data.url || "");
                                                        return Promise.resolve();
                                                    }
                                                    return Promise.reject("Share interface not initialized");
                                                };
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        return false
                                    }
                                    try {
                                        val intent = if (url.startsWith("intent://")) {
                                            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        } else {
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        }
                                        ctx.startActivity(intent)
                                        return true
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Related application not found", Toast.LENGTH_SHORT).show()
                                        return true
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    // Handle errors for primary page resource failure
                                    if (request?.isForMainFrame == true) {
                                        hasError = true
                                        errorDescription = error?.description?.toString() ?: "Unknown loading error"
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    loadProgress = newProgress.toFloat() / 100f
                                    if (newProgress >= 100) {
                                        isPageLoading = false
                                    }
                                }

                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                                    this@MainActivity.filePathCallback = filePathCallback

                                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "image/*"
                                    }
                                    try {
                                        fileChooserLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                                        this@MainActivity.filePathCallback = null
                                        Toast.makeText(ctx, "File chooser failed to launch", Toast.LENGTH_SHORT).show()
                                        return false
                                    }
                                    return true
                                }

                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    request?.grant(request.resources)
                                }
                            }

                            // Professional Settings for HTML5 content and advertisements
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                allowFileAccess = true
                                allowContentAccess = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            addJavascriptInterface(WebShareInterface(), "AndroidShare")

                            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                                try {
                                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                                        setMimeType(mimetype)
                                        addRequestHeader("User-Agent", userAgent)
                                        val cookie = CookieManager.getInstance().getCookie(url)
                                        addRequestHeader("Cookie", cookie)
                                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype) ?: "Download"
                                        setTitle(fileName)
                                        setDescription("Downloading PDF or File...")
                                        allowScanningByMediaScanner()
                                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                    }
                                    val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
                                    dm.enqueue(request)
                                    Toast.makeText(ctx, "Downloading PDF or File...", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse(url)
                                        }
                                        ctx.startActivity(intent)
                                    } catch (ex: Exception) {
                                        Toast.makeText(ctx, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }

                            webViewRef = this
                            loadUrl(targetUrl)
                        }
                    },
                    update = { view ->
                        webViewRef = view
                    }
                )

                // Animated Exit Confirmation Dialog Overlay
                AnimatedVisibility(
                    visible = showExitDialog,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                showExitDialog = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedVisibility(
                            visible = showExitDialog,
                            enter = fadeIn() + scaleIn(initialScale = 0.85f),
                            exit = fadeOut() + scaleOut(targetScale = 0.85f),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 340.dp)
                                    .clickable(enabled = false) {}, // Prevent clicks inside card from closing
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Custom visual illustration decoration in dialog
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .size(54.dp)
                                            .padding(bottom = 12.dp)
                                    )

                                    Text(
                                        text = "Exit Application?",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "Are you sure you want to close The Education Hills? Any unsaved progress will be lost.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // "No" button dismisses dialog
                                        OutlinedButton(
                                            onClick = { showExitDialog = false },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .testTag("exit_cancel_button"),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "No",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // "Yes" button exits the application task finished
                                        Button(
                                            onClick = {
                                                finish()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .testTag("exit_confirm_button"),
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Text(
                                                text = "Yes",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AdmobBannerAdBlock(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(60.dp),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = if (BuildConfig.ADMOB_BANNER_AD_ID.isNotEmpty()) {
                        BuildConfig.ADMOB_BANNER_AD_ID
                    } else {
                        "ca-app-pub-3940256099942544/6300978111" // Official AdMob Test Banner ID
                    }
                    loadAd(AdRequest.Builder().build())
                }
            },
            update = { adView ->
                // Banner view persists natively
            }
        )
    }
}
