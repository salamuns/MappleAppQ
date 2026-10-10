package rip.mapple.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://mapple.rip"
        private const val OFFLINE_URL = "file:///android_asset/offline.html"

        // Daftar awal domain iklan yang diblokir. Tambahkan domain lain di sini jika perlu.
        private val BLOCKED_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adnxs.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "exoclick.com",
            "adsterra.com",
            "juicyads.com",
            "trafficjunky.net",
            "clickadu.com",
            "hilltopads.net",
            "onclickads.net"
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var failedUrl: String? = null

    // Domain yang boleh dibuka di jendela utama (ditambah otomatis dari halaman pertama)
    private val allowedHosts = mutableSetOf("mapple.rip")
    private var firstLoadDone = false

    // Untuk upload file
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    private fun hostMatches(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }

    private fun isBlockedHost(host: String?): Boolean {
        if (host == null) return false
        return BLOCKED_HOSTS.any { hostMatches(host, it) }
    }

    private fun isAllowedHost(host: String?): Boolean {
        if (host == null) return true
        return allowedHosts.any { hostMatches(host, it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        root = FrameLayout(this)
        webView = WebView(this)
        swipe = SwipeRefreshLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
            setOnRefreshListener {
                if (webView.url?.startsWith(OFFLINE_URL) == true) {
                    webView.loadUrl(failedUrl ?: START_URL)
                } else {
                    webView.reload()
                }
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(swipe, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8))
        setContentView(root)
        applyFullscreen()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Agar window.open() bisa dicegat (dan diblokir) lewat onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host
                return when (uri.scheme) {
                    "http", "https" -> {
                        if (!request.isForMainFrame) {
                            false
                        } else if (!firstLoadDone && host != null) {
                            // Halaman pertama (termasuk redirect resminya) selalu diizinkan
                            allowedHosts.add(host)
                            false
                        } else if (isAllowedHost(host)) {
                            false
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Redirect diblokir: $host",
                                Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                    }
                    "file", "about", "data", "blob", "javascript" -> false
                    "mailto", "tel" -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                        true
                    }
                    else -> true
                }
            }

            // Blokir permintaan ke domain iklan
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isBlockedHost(request.url.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                if (url != null && url.startsWith("http")) firstLoadDone = true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !request.url.toString().startsWith(OFFLINE_URL)) {
                    failedUrl = request.url.toString()
                    view.loadUrl(OFFLINE_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            // Blokir semua popup / jendela baru (kebanyakan iklan)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                Toast.makeText(this@MainActivity, "Popup diblokir", Toast.LENGTH_SHORT).show()
                return false
            }

            // Upload file
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (callback == null || params == null) return false
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    fileChooserLauncher.launch(params.createIntent())
                } catch (e: Exception) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Tidak bisa membuka pemilih file", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                swipe.visibility = View.GONE
                root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                applyFullscreen()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                swipe.visibility = View.VISIBLE
                applyFullscreen()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(START_URL)
        }
    }

    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { webView.onPause(); super.onPause() }

    override fun onDestroy() {
        root.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}
