package rip.mapple.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Message
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
        
        // Inisialisasi WebView (sesuaikan dengan Layout / ViewBinding milik Anda)
        webView = WebView(this)
        setContentView(webView)

        setupWebViewSettings()
        setupWebViewClients()

        // Muat URL utama aplikasi Anda
        webView.loadUrl("https://example.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings() {
        val settings: WebSettings = webView.settings

        // 1. Aktifkan JavaScript yang dibutuhkan situs web
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // 2. MATIKAN izin pembuat window/pop-up otomatis dari JavaScript
        settings.javaScriptCanOpenWindowsAutomatically = false

        // 3. MATIKAN dukungan banyak window (mencegah target="_blank" membuka tab/window baru)
        settings.setSupportMultipleWindows(false)
    }

    private fun setupWebViewClients() {
        // Blokir pembuatan window baru via WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Mengembalikan nilai 'false' memberitahu sistem untuk menolak pembukaan pop-up
                return false
            }
        }

        // Kontrol navigasi URL via WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Contoh logika penanganan URL:
                // Hanya izinkan navigasi jika URL berasal dari domain utama Anda
                if (url.contains("example.com")) {
                    return false // 'false' berarti WebView akan memuat URL di halaman saat ini
                }

                // Blokir pengalihan/redirect ke URL luar atau iklan
                return true // 'true' berarti permintaan navigasi ditolak/dibatalkan
            }
        }
    }

    // Tangani tombol Back fisik agar navigasi WebView bisa kembali ke halaman sebelumnya
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
 
