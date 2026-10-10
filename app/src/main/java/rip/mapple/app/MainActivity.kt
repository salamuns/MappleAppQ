package rip.mapple.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this)
        setContentView(webView)

        val webSettings: WebSettings = webView.settings
        
        // Mengaktifkan JavaScript yang dibutuhkan oleh situs web
        webSettings.javaScriptEnabled = true
        
        // --- PENGATURAN PEMBLOKIRAN POP-UP ---
        // Memblokir JavaScript agar tidak membuka window/pop-up otomatis
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        
        // Mematikan dukungan banyak jendela (mencegah iklan membuka tab/window baru)
        webSettings.setSupportMultipleWindows(false)
        
        // Optimasi penyimpanan & cache
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.allowFileAccess = false

        // Menangani navigasi di dalam WebView
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Meneruskan skema khusus (seperti whatsapp://, intent://, mailto:) ke aplikasi eksternal jika diperlukan
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false // Tetap muat di dalam WebView
                }
                
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true // Blokir jika intent eksternal tidak dapat ditangani
                }
            }
        }

        // Menangani event UI browser & menolak pembuatan window pop-up baru
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // Mengembalikan false untuk menolak pembuatan jendela pop-up baru
                return false
            }
        }

        // Muat URL utama aplikasi Anda
        webView.loadUrl("https://google.com")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
