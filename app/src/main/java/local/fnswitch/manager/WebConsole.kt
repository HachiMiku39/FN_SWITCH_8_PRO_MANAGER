package local.fnswitch.manager

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream

object ConsolePolicy {
    const val ORIGIN = "https://switch.invalid"
    fun path(url: String): String {
        val u = url.toHttpUrlOrNull() ?: throw SwitchFailure(Failure.PARAMETER)
        if (u.scheme != "https" || u.host != "switch.invalid" || u.port != 443 || u.username.isNotEmpty() || u.password.isNotEmpty()) throw SwitchFailure(Failure.PARAMETER)
        return u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
    }
    fun inject(html: String, target: String = ""): String {
        val bridge = "<script src=\"/__fn/bridge.js\"></script>"
        val head = Regex("(?i)<head(?:\\s[^>]*)?>").find(html)
        var result = if (head != null) html.replaceRange(head.range, head.value + bridge) else html
        if (result == html) result = bridge + html
        if (target.isNotBlank()) result = result.replace(Regex("var defPage\\s*=\\s*'[^']*';"), { "var defPage = ${JSONObject.quote(target).replace("<", "\\u003C").replace(">", "\\u003E")};" })
        return result
    }
}
class ConsoleBridge(private val api: SwitchApi, private val onLogout: () -> Unit = {}) {
    @Volatile var active = true
    @JavascriptInterface fun exchange(payload: String): String {
        try {
            if (!active || payload.length > 47 * 1024 * 1024) throw SwitchFailure(Failure.PARAMETER)
            val request = JSONObject(payload)
            val rawPath = request.getString("path")
            val path = ConsolePolicy.path(ConsolePolicy.ORIGIN + rawPath)
            val method = request.getString("method")
            val body = request.optString("body").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
            val result = api.proxy(method, path, body, request.optString("contentType").takeIf { it.isNotBlank() })
            if (method == "POST" && api.address.base.resolve(path)?.queryParameter("cmd") == "home_logout" && runCatching { JSONObject(result.bytes.toString(Charsets.UTF_8)).optString("status") == "ok" }.getOrDefault(false)) onLogout()
            return JSONObject().put("status", result.status).put("reason", result.reason).put("headers", JSONObject(result.headers.filterKeys { !it.equals("set-cookie", true) })).put("body", Base64.encodeToString(result.bytes, Base64.NO_WRAP)).toString()
        } catch (_: Exception) { return JSONObject().put("status", 502).put("reason", "Device request failed").put("headers", JSONObject()).put("body", "").toString() }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable fun WebConsoleScreen(api: SwitchApi, initialPath: String, close: () -> Unit, export: (ExportFile) -> Unit, disconnect: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)); fileCallback = null }
    val bridge: ConsoleBridge = remember(api) { ConsoleBridge(api) { android.os.Handler(android.os.Looper.getMainLooper()).post { disconnect() } } }; val popups = remember { mutableListOf<Dialog>() }
    val bridgeJs = remember { context.assets.open("console-bridge.js").use { it.readBytes() } }
    fun blocked(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    fun install(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false; databaseEnabled = false; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false; useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        addDeviceInterface(view, bridge)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = runCatching { ConsolePolicy.path(request.url.toString()); false }.getOrElse { android.widget.Toast.makeText(context, context.getString(R.string.web_blocked), android.widget.Toast.LENGTH_SHORT).show(); true }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse {
                try {
                    val path = ConsolePolicy.path(request.url.toString())
                    if (!bridge.active || request.method != "GET") return blocked()
                    if (path.substringBefore('?') == "/__fn/bridge.js") return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(bridgeJs))
                    val response = api.proxy("GET", path)
                    val type = response.contentType.substringBefore(';').trim()
                    val html = type.equals("text/html", true)
                    val bytes = if (html) ConsolePolicy.inject(response.bytes.toString(Charsets.UTF_8), if (path.substringBefore('?') == "/home.html") initialPath else "").toByteArray() else response.bytes
                    val headers = response.headers.filterKeys { key -> key.lowercase() !in listOf("set-cookie", "content-length", "content-encoding", "transfer-encoding", "content-security-policy", "location") }.toMutableMap()
                    headers["Cache-Control"] = "no-store"
                    headers["Content-Security-Policy"] = "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'self' about:; form-action 'self'; base-uri 'self'"
                    val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value
                    if (location != null) {
                        val remote = api.address.base.resolve(location) ?: return blocked()
                        if (remote.host != api.address.host || remote.scheme != "https" || remote.port != api.address.port) return blocked()
                        // WebResourceResponse cannot represent redirects; safely follow same-device GET only.
                        val redirect = api.proxy("GET", remote.encodedPath + (remote.encodedQuery?.let { "?$it" } ?: ""))
                        return WebResourceResponse(redirect.contentType.substringBefore(';'), "UTF-8", redirect.status, redirect.reason, headers, ByteArrayInputStream(if (redirect.contentType.contains("text/html")) ConsolePolicy.inject(redirect.bytes.toString(Charsets.UTF_8)).toByteArray() else redirect.bytes))
                    }
                    if (response.status in 300..399) return blocked()
                    return WebResourceResponse(type, if (type.startsWith("text/") || type.contains("javascript") || type.contains("json")) "UTF-8" else null, response.status, response.reason, headers, ByteArrayInputStream(bytes))
                } catch (_: Exception) { return WebResourceResponse("text/plain", "UTF-8", 502, "Device unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf())) }
            }
            // No SSL errors are bypassed. switch.invalid never uses a network socket: pinned native HTTPS handles all content.
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, sslError: android.net.http.SslError) { handler.cancel() }
            override fun onPageFinished(v: WebView, url: String) { if (v == web) { loading = false; error = false } }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, e: WebResourceError) { if (request.isForMainFrame) { loading = false; error = true } }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) { loading = false; error = true } }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = callback
                return try { picker.launch(params.createIntent()); true } catch (_: Exception) { fileCallback?.onReceiveValue(null); fileCallback = null; false }
            }
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, message: Message): Boolean {
                val child = WebView(context); install(child)
                val dialog = Dialog(context).apply { setContentView(child); setOnDismissListener { child.removeJavascriptInterface("FnDevice"); child.stopLoading(); child.destroy(); popups.remove(this) } }
                popups.add(dialog); dialog.show(); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                (message.obj as WebView.WebViewTransport).webView = child; message.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView) { popups.firstOrNull { it.findViewById<android.view.View>(android.R.id.content)?.let { root -> root is ViewGroup && root.getChildAt(0) == window } == true }?.dismiss() }
            override fun onJsAlert(v: WebView, url: String, message: String, result: JsResult): Boolean { android.app.AlertDialog.Builder(context).setMessage(message).setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }.setOnCancelListener { result.cancel() }.show(); return true }
            override fun onJsConfirm(v: WebView, url: String, message: String, result: JsResult): Boolean { android.app.AlertDialog.Builder(context).setMessage(message).setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }.setOnCancelListener { result.cancel() }.show(); return true }
        }
        view.setDownloadListener { url, _, _, mime, _ ->
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) { val path = ConsolePolicy.path(url); val response = api.proxy("GET", path); if (response.status != 200 || response.bytes.isEmpty()) throw SwitchFailure(Failure.FILE); ExportFile("FN-Switch-${System.currentTimeMillis()}." + if (mime.contains("text")) "cfg" else "bin", response.bytes) }
                    export(file)
                } catch (_: Exception) { error = true }
            }
        }
    }
    BackHandler { if (popups.isNotEmpty()) popups.last().dismiss() else if (web?.canGoBack() == true) web?.goBack() else close() }
    DisposableEffect(api) { onDispose { bridge.active = false; fileCallback?.onReceiveValue(null); fileCallback = null; popups.toList().forEach { it.dismiss() }; web?.apply { removeJavascriptInterface("FnDevice"); stopLoading(); clearHistory(); clearCache(true); destroy() }; web = null } }
    Scaffold(topBar = { TopAppBar(title = { Text(t(R.string.full_web)) }, navigationIcon = { IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, t(R.string.back)) } }, actions = { IconButton({ loading = true; web?.reload() }) { Icon(Icons.Outlined.Refresh, t(R.string.refresh)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(t(R.string.web_loading)) }
            if (error) Text(t(R.string.error_connection))
            AndroidView(factory = { WebView(it).also { v -> web = v; install(v); v.loadUrl(ConsolePolicy.ORIGIN + "/home.html") } }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private fun addDeviceInterface(view: WebView, bridge: ConsoleBridge) { view.addJavascriptInterface(bridge, "FnDevice") }
