package rip.mapple.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://mapple.rip"
        private const val OFFLINE_URL = "file:///android_asset/offline.html"

        // Domain iklan/pelacak yang sudah teridentifikasi. Dimasukkan sekali ke daftar blokir
        // saat aplikasi pertama kali dibuka; bisa diubah lewat sentuhan 3 jari.
        private val DEFAULT_BLOCKED = setOf(
            "maonhrxyewyjr.com",
            "connatix.com",
            "id5-sync.com",
            "thirstacock.com",
            "saguingcomae.cfd",
            "loggatsiyo.cfd",
            "cowedd4855ws.sbs",
            "usrpubtrk.com",
            "adsco.re",
            "klhtuvqgsvyzs.online",
            "adexchangerapid.com",
            "premiumvertising.com",
            "ketubahhuesrammed.cyou",
            "meatusbyway.cfd",
            "dtscout.com",
            "llvpn.com",
            "briguefidibus.com",
            "ntwkbc42.com",
            "ntwkbc21.com",
            "profitableratecpmnetwork.com",
            "xadsmart.com",
            "urbanfungose.com",
            "tiktokcdn-us.com"
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var failedUrl: String? = null

    // Pencatat domain: domain yang dipanggil halaman + daftar blokir pilihan pengguna
    private val seenHosts = ConcurrentHashMap<String, Long>()
    @Volatile private var userBlocked: Set<String> = emptySet()
    private var lastDialogMs = 0L
    private val prefs by lazy { getSharedPreferences("mapple", MODE_PRIVATE) }

    // Untuk upload file
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // Ambil domain induk, misal "www.mapple.rip" -> "mapple.rip"
    private fun rootDomain(host: String): String {
        val parts = host.lowercase().split(".")
        return if (parts.size <= 2) host.lowercase() else parts.takeLast(2).joinToString(".")
    }

    // true jika host berasal dari situs yang sedang dibuka (atau mapple.rip)
    private fun isSameSite(host: String?): Boolean {
        if (host == null) return false
        val target = rootDomain(host)
        if (target == "mapple.rip") return true
        val current = webView.url?.let { Uri.parse(it).host }
        return current != null && rootDomain(current) == target
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val savedBlocked = prefs.getStringSet("blocked", null)?.toSet() ?: emptySet()
        if (!prefs.getBoolean("seed_v1", false)) {
            val merged = savedBlocked + DEFAULT_BLOCKED
            prefs.edit()
                .putStringSet("blocked", HashSet(merged))
                .putBoolean("seed_v1", true)
                .apply()
            userBlocked = merged
        } else {
            userBlocked = savedBlocked
        }

        // Tampilan penuh sampai ke area notch/poni
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
            // Jendela baru dicegat lewat onCreateWindow; popup otomatis tanpa klik
            // ditolak langsung oleh WebView.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme) {
                    // Semua halaman web dibuka di dalam aplikasi
                    "http", "https", "file", "about", "data", "blob", "javascript" -> false
                    // Email / telepon diserahkan ke aplikasi terkait
                    "mailto", "tel" -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                        true
                    }
                    else -> true
                }
            }

            // Catat domain yang dipanggil, dan tolak domain yang Anda blokir
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host
                if (host != null) {
                    seenHosts[host] = System.currentTimeMillis()
                    if (userBlocked.any { host == it || host.endsWith(".$it") }) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
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

            // Popup / jendela baru: hanya situs sendiri yang dibuka, domain lain diblokir
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    private var handled = false

                    private fun handle(v: WebView, url: String?) {
                        if (handled || url == null || url == "about:blank") return
                        handled = true
                        val host = Uri.parse(url).host
                        if (isSameSite(host)) {
                            webView.loadUrl(url)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Popup iklan diblokir",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        v.stopLoading()
                        Handler(Looper.getMainLooper()).post { v.destroy() }
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        handle(v, request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        handle(v, url)
                    }
                }
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
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

            // Video fullscreen
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

    // Sentuh dengan 3 jari sekaligus untuk membuka daftar domain
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 3) {
            showDomainDialog()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showDomainDialog() {
        if (isFinishing) return
        val now = System.currentTimeMillis()
        if (now - lastDialogMs < 1500) return
        lastDialogMs = now

        val seen = seenHosts.entries.sortedByDescending { it.value }.map { it.key }.take(100)
        val blocked = userBlocked
        val items = (seen + blocked.filter { it !in seen }).toTypedArray()
        if (items.isEmpty()) {
            Toast.makeText(this, "Belum ada domain tercatat", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(items.size) { items[it] in blocked }

        AlertDialog.Builder(this)
            .setTitle("Domain yang dipanggil (terbaru di atas). Centang untuk memblokir.")
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Terapkan") { _, _ ->
                val newSet = HashSet<String>()
                for (i in items.indices) if (checked[i]) newSet.add(items[i])
                userBlocked = newSet
                prefs.edit().putStringSet("blocked", newSet).apply()
                webView.reload()
            }
            .setNeutralButton("Salin daftar") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("domain", items.joinToString("\n")))
                Toast.makeText(this, "Daftar disalin", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // Sembunyikan status bar & navigation bar. Geser dari tepi layar untuk memunculkannya sebentar.
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
