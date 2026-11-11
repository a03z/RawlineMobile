package app.rawline.mobile.bg.carrier

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.rawline.mobile.ktx.app

/**
 * FreeTurn's automatic check as its own app runs it (OnCaptcha with manual=false): the client's local page is loaded
 * in a WebView no one sees, the page hands the proof of work to the client itself, and the client closes it.
 */
internal object FreeturnPowPage {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val local = Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}(/.*)?")

    fun allowed(url: String) = local.matches(url)

    @SuppressLint("SetJavaScriptEnabled")
    fun open(url: String) = main.post {
        close0()
        if (!allowed(url)) return@post
        runCatching {
            val view = WebView(app)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            // The page stays on the client's proxy; anything else is not followed.
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = !allowed(request.url.toString())
            }
            val density = app.resources.displayMetrics.density
            val width = (360 * density).toInt(); val height = (640 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            view.onResume()
            web = view
            view.loadUrl(url)
            // The client gives up long before this; the page never outlives a forgotten close.
            main.postDelayed({ if (web === view) close0() }, 60_000)
        }
    }

    fun close() = main.post { close0() }

    private fun close0() {
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        web = null
    }
}
