package rip.mapple.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

        // true = pembersih iklan JavaScript aktif. Jika ada bagian website yang hilang/tidak
        // bisa diklik, ubah menjadi false.
        private const val ENABLE_CLEANER = true

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
        // menyembunyikan iframe iklan, overlay iklan di atas pemutar video, dan
        // elemen yang ditekan-tahan (700 ms) oleh pengguna.
        private val CLEANER_JS = """
(function () {
  if (window.__mappleCleaner) return;
  window.__mappleCleaner = true;

  var AD_SIZES = ["300x250","336x280","728x90","320x50","468x60","300x600","160x600",
                  "970x250","320x100","970x90","250x250","300x50","120x600","180x150","200x200"];
  var AD_SRC = /(doubleclick|googlesyndication|googleadservices|adservice|adsterra|highperformanceformat|exoclick|exosrv|realsrv|exdynsrv|popads|popcash|propeller|juicyads|trafficjunky|clickadu|hilltopads|onclickads|adnxs|adcash|mgid|taboola|outbrain|revcontent|tsyndicate|a-ads|coinzilla|bidvertiser|adspyglass|ero-advertising)/i;
  var CSS = 'ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="adsterra"]{display:none!important}';

  function hostOf(u) {
    try { return new URL(u, location.href).host; } catch (e) { return ''; }
  }

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

  /* ---------- 1. iframe iklan & overlay level atas ---------- */
  function scanBasic() {
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

  /* ---------- 2. lapisan yang menutupi pemutar video ---------- */
  function mainTarget() {
    var best = null, bestA = 0, i, r, a;
    var vids = document.getElementsByTagName('video');
    for (i = 0; i < vids.length; i++) {
      r = vids[i].getBoundingClientRect();
      a = r.width * r.height;
      if (a > bestA && r.width >= 200 && r.height >= 100) { best = vids[i]; bestA = a; }
    }
    if (best) return best;
    var fr = document.getElementsByTagName('iframe');
    for (i = 0; i < fr.length; i++) {
      if (fr[i].getAttribute('data-mapple-hidden')) continue;
      if (AD_SRC.test(fr[i].src || '')) continue;
      r = fr[i].getBoundingClientRect();
      a = r.width * r.height;
      if (a > bestA && r.width >= 200 && r.height >= 100) { best = fr[i]; bestA = a; }
    }
    return best;
  }

  function looksLikeAd(box, targetArea) {
    if (box.tagName === 'IFRAME' || box.tagName === 'INS') return true;
    if (box.querySelector('iframe, ins')) return true;
    var links = box.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var h = hostOf(links[i].getAttribute('href'));
      if (h && h !== location.host) return true;
    }
    var imgs = box.querySelectorAll('img[src]');
    for (var j = 0; j < imgs.length; j++) {
      var ir = imgs[j].getBoundingClientRect();
      var ih = hostOf(imgs[j].getAttribute('src'));
      if (ih && ih !== location.host && ir.width * ir.height >= targetArea * 0.25) return true;
    }
    return false;
  }

  function checkCover() {
    var t = mainTarget();
    if (!t) return;
    var r = t.getBoundingClientRect();
    var ta = r.width * r.height;
    var pts = [[0.5, 0.5], [0.3, 0.5], [0.7, 0.5], [0.5, 0.3], [0.5, 0.7]];
    for (var k = 0; k < pts.length; k++) {
      var x = r.left + r.width * pts[k][0];
      var y = r.top + r.height * pts[k][1];
      if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) continue;
      var e = document.elementFromPoint(x, y);
      if (!e || e === t || t.contains(e) || e.contains(t)) continue;
      if (e === document.body || e === document.documentElement) continue;
      var box = e;
      while (box.parentElement && box.parentElement !== document.body &&
             box.parentElement !== document.documentElement && !box.parentElement.contains(t)) {
        box = box.parentElement;
      }
      if (box.getAttribute('data-mapple-hidden')) continue;
      var br = box.getBoundingClientRect();
      if (br.width * br.height >= ta * 0.4 && looksLikeAd(box, ta)) {
        hide(box);
        return;
      }
    }
  }

  function scan() {
    try { scanBasic(); } catch (e) {}
    try { checkCover(); } catch (e) {}
  }

  /* ---------- 3. tekan-tahan untuk menyembunyikan elemen ---------- */
  function describe(el) {
    var d = el.tagName.toLowerCase();
    if (el.id) d += '#' + el.id;
    if (el.className && typeof el.className === 'string') {
      d += '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.');
    }
    var u = el.src || el.href || '';
    if (u) d += ' [' + hostOf(u) + ']';
    return d.slice(0, 120);
  }

  function toast(msg) {
    try {
      var t = document.createElement('div');
      t.textContent = msg;
      t.style.cssText = 'position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;' +
        'background:rgba(0,0,0,.8);color:#fff;font:12px sans-serif;padding:8px;' +
        'border-radius:6px;pointer-events:none';
      (document.body || document.documentElement).appendChild(t);
      setTimeout(function () { try { t.remove(); } catch (e) {} }, 4000);
    } catch (e) {}
  }

  var lpTimer = null, lpX = 0, lpY = 0;
  function cancelLp() { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } }

  function onTouchStart(ev) {
    if (!ev.touches || ev.touches.length !== 1) { cancelLp(); return; }
    lpX = ev.touches[0].clientX;
    lpY = ev.touches[0].clientY;
    cancelLp();
    lpTimer = setTimeout(function () {
      lpTimer = null;
      var el = document.elementFromPoint(lpX, lpY);
      if (!el || el === document.body || el === document.documentElement) return;
      if (el.tagName === 'VIDEO') return;
      var box = positionedAncestor(el) || el;
      hide(box);
      toast('Disembunyikan: ' + describe(box));
    }, 700);
  }

  function onTouchMove(ev) {
    if (!lpTimer || !ev.touches || !ev.touches.length) return;
    var dx = ev.touches[0].clientX - lpX, dy = ev.touches[0].clientY - lpY;
    if (dx * dx + dy * dy > 144) cancelLp();
  }

  function start() {
    try {
      var st = document.createElement('style');
      st.textContent = CSS;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
    scan();

    var timer = null;
    function schedule() {
      if (timer) return;
      timer = setTimeout(function () { timer = null; scan(); }, 300);
    }
    try {
      new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    setInterval(scan, 1000);

    document.addEventListener('touchstart', onTouchStart, { passive: true, capture: true });
    document.addEventListener('touchmove', onTouchMove, { passive: true, capture: true });
    document.addEventListener('touchend', cancelLp, { passive: true, capture: true });
    document.addEventListener('touchcancel', cancelLp, { passive: true, capture: true });
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
            // Link "tab baru" dan window.open() akibat klik dibuka di jendela yang sama;
            // popup otomatis tanpa klik tetap ditolak.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        // Pasang pembersih iklan di setiap halaman dan iframe (jika didukung WebView)
        if (ENABLE_CLEANER && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, CLEANER_JS, setOf("*"))
            cleanerViaDocStart = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host
                return when (uri.scheme) {
                    "http", "https" -> {
                        // Semua link bebas dibuka, kecuali domain iklan yang dikenal
                        if (request.isForMainFrame && isBlockedHost(host)) {
                            Toast.makeText(
                                this@MainActivity,
                                "Iklan diblokir: $host",
                                Toast.LENGTH_SHORT
                            ).show()
                            true
                        } else {
                            false
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
                if (ENABLE_CLEANER && !cleanerViaDocStart && url != null && url.startsWith("http")) {
                    view.evaluateJavascript(CLEANER_JS, null)
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
