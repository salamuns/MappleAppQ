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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://mapple.rip"
        private const val OFFLINE_URL = "file:///android_asset/offline.html"

        // Daftar domain iklan yang diblokir. Tambahkan domain lain di sini jika perlu.
        private val BLOCKED_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "2mdn.net", "amazon-adsystem.com",
            "adnxs.com", "adform.net", "pubmatic.com", "rubiconproject.com",
            "criteo.com", "openx.net", "smartadserver.com",
            "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
            "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com",
            "adsterra.com", "highperformanceformat.com",
            "juicyads.com", "trafficjunky.net", "clickadu.com", "hilltopads.net",
            "onclickads.net", "adcash.com", "tsyndicate.com", "a-ads.com",
            "coinzilla.com", "bidvertiser.com", "adspyglass.com", "ero-advertising.com",
            "mgid.com", "taboola.com", "outbrain.com", "revcontent.com", "zedo.com"
        )

        // Skrip yang berjalan di halaman dan di semua iframe (pemutar video):
        // menyembunyikan iframe iklan (ukuran banner standar / domain iklan) dan
        // lapisan overlay iklan yang menutupi layar.
        private val CLEANER_JS = """
(function () {
  if (window.__mappleCleaner) return;
  window.__mappleCleaner = true;

  var AD_SIZES = ["300x250","336x280","728x90","320x50","468x60","300x600","160x600",
                  "970x250","320x100","970x90","250x250","300x50","120x600","180x150","200x200"];
  var AD_SRC = /(doubleclick|googlesyndication|googleadservices|adservice|adsterra|highperformanceformat|exoclick|exosrv|realsrv|exdynsrv|popads|popcash|propeller|juicyads|trafficjunky|clickadu|hilltopads|onclickads|adnxs|adcash|mgid|taboola|outbrain|revcontent|tsyndicate|a-ads|coinzilla|bidvertiser|adspyglass|ero-advertising)/i;
  var CSS = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="adsterra"]{display:none!important}';

  function hide(el) {
    try {
      el.style.setProperty('display', 'none', 'important');
      el.setAttribute('data-mapple-hidden', '1');
    } catch (e) {}
  }

  function positionedAncestor(el) {
    var p = el, n = 0;
    while (p && p !== document.body && p !== document.documentElement && n < 5) {
      var cs = getComputedStyle(p);
      if (cs.position === 'fixed' || cs.position === 'absolute' || cs.position === 'sticky') return p;
      p = p.parentElement;
      n++;
    }
    return null;
  }

  function scan() {
    if (!document.body) return;
    var vw = window.innerWidth, vh = window.innerHeight;

    var frames = document.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (f.getAttribute('data-mapple-hidden')) continue;
      var r = f.getBoundingClientRect();
      var key = Math.round(r.width) + 'x' + Math.round(r.height);
      if (AD_SRC.test(f.src || '') || AD_SIZES.indexOf(key) >= 0) {
        var box = positionedAncestor(f);
        if (box) {
          var br = box.getBoundingClientRect();
          if (br.width * br.height < vw * vh * 0.6) { hide(box); continue; }
        }
        hide(f);
      }
    }

    var kids = document.body.children;
    for (var j = 0; j < kids.length; j++) {
      var el = kids[j];
      if (el.getAttribute('data-mapple-hidden')) continue;
      var tag = el.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK') continue;
      var cs = getComputedStyle(el);
      if (cs.display === 'none') continue;
      if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      if (el.querySelector('video')) continue;
      if (el.querySelector('iframe:not([data-mapple-hidden])')) continue;
      var z = parseInt(cs.zIndex, 10) || 0;
      var rect = el.getBoundingClientRect();
      var big = rect.width * rect.height > vw * vh * 0.5;
      var empty = (el.innerText || '').replace(/\s+/g, '') === '' &&
                  !el.querySelector('input,button,img,svg');
      if (z >= 2147483000) { hide(el); }
      else if (big && z >= 999 && empty) { hide(el); }
    }
  }

  var timer = null;
  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      try { scan(); } catch (e) {}
    }, 400);
  }

  function start() {
    try {
      var st = document.createElement('style');
      st.textContent = CSS;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
    try { scan(); } catch (e) {}
    try {
      new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    setInterval(function () { try { scan(); } catch (e) {} }, 2000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
"""
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
    private var cleanerViaDocStart = false

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

        // Pasang pembersih iklan di setiap halaman dan iframe (jika didukung WebView)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, CLEANER_JS, setOf("*"))
            cleanerViaDocStart = true
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
                if (url != null && url.startsWith("http")) {
                    firstLoadDone = true
                    if (!cleanerViaDocStart) view.evaluateJavascript(CLEANER_JS, null)
                }
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
