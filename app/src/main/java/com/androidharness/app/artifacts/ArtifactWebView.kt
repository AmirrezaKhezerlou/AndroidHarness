package com.androidharness.app.artifacts

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.MotionEvent
import android.webkit.*
import java.io.ByteArrayInputStream

/** No bridge, storage, external navigation, permissions, downloads, or app/workspace loader. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ArtifactWebView(
    context: Context,
    private val onLoading: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onUnavailable: (String) -> Unit = {},
) : WebView(context) {
    var isReleased = false
        private set
    var revisionKey: String? = null
        private set

    init {
        settings.apply {
            javaScriptEnabled = true
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, 200)
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            setSaveFormData(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        removeJavascriptInterface("searchBoxJavaBridge_")
        removeJavascriptInterface("accessibility")
        removeJavascriptInterface("accessibilityTraversal")
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        contentDescription = "Interactive concept preview"
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                request.url.toString() !in setOf("about:blank", "about:srcdoc")
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = url !in setOf("about:blank", "about:srcdoc")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.scheme in setOf("data", "blob") || request.url.toString() in setOf("about:blank", "about:srcdoc")) null else blocked()
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) { onLoading(false); onUnavailable("Couldn’t load the preview. Reload to try again.") }
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { onLoading(true) }
            override fun onPageFinished(view: WebView?, url: String?) { onLoading(false) }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                (view as? ArtifactWebView)?.release()
                onLoading(false); onUnavailable("Preview stopped. Reload to try again."); return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                callback?.onReceiveValue(null); return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean { result?.cancel(); return true }
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    onError("Preview error: ${message.message().take(160)}")
                }
                return true
            }
        }
        setDownloadListener { _, _, _, _, _ -> onError("Use Save HTML to export this concept.") }
    }

    fun show(artifact: ChatArtifact, dark: Boolean) {
        val key = "${artifact.revision}:$dark"
        if (revisionKey == key) return
        revisionKey = key
        onLoading(true)
        loadDataWithBaseURL(ArtifactSandbox.origin(artifact), ArtifactSandbox.frameDocument(artifact, dark), "text/html", "UTF-8", null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        (parent as? android.view.ViewGroup)?.removeView(this)
        stopLoading()
        webChromeClient = null
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
