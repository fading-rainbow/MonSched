package cn.qingke.schedule.importer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import cn.qingke.schedule.ui.QingKeTheme
import kotlinx.coroutines.*
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume

class WebImportActivity : ComponentActivity() {
    private var web: WebView? = null
    private var loading by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var currentUrl by mutableStateOf("")
    private var readJob: Job? = null
    private var system = "zhengfang"
    private var configuredUrl = ""
    private var allowHttp = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra("url") ?: SchoolConnection.QIT_URL
        configuredUrl = url
        system = intent.getStringExtra("system") ?: "zhengfang"
        allowHttp = intent.getBooleanExtra("allowHttp", false)
        if (!validUrl(url)) { finish(); return }
        val prefs = getSharedPreferences("preferences", 0)
        setContent {
            QingKeTheme(prefs.getString("theme", "system") ?: "system") {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }
                BackHandler {
                    if (web?.canGoBack() == true) web?.goBack() else finish()
                }
                Scaffold(
                    topBar = { Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row { Text("教务导入", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(12.dp))
                            IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "关闭教务页面") } }
                        Text(Uri.parse(currentUrl.ifEmpty { url }).host.orEmpty(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                        Text(if (Uri.parse(currentUrl.ifEmpty { url }).scheme == "http")
                            "当前入口使用 HTTP；请只在可信网络登录。登录后进入学生课表，选择学期并查询。"
                            else "登录后进入学生课表，选择学年学期并查询，再点击读取课表。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    } },
                    bottomBar = { Row(Modifier.navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { if (web?.canGoBack() == true) web?.goBack() }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "网页后退") }
                        IconButton(onClick = { web?.reload() }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "刷新网页") }
                        OutlinedButton(onClick = {
                            val entry = SchoolConnection.zhengfangEntry(web?.url ?: url)
                            if (entry != null && validUrl(entry)) web?.loadUrl(entry)
                            else error = "请先完成登录，再使用网页中的菜单打开学生课表"
                        }, enabled = !busy && system == "zhengfang") { Text("课表入口") }
                        Button(onClick = { readTimetable() }, enabled = !busy && !loading, modifier = Modifier.weight(1f)) {
                            Text(if (busy) "读取中…" else "读取课表")
                        }
                    } }, snackbarHost = { SnackbarHost(snackbar) }
                ) { padding ->
                    AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context ->
                        WebView(context).apply {
                            web = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                            settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                            // School pages are generally desktop-oriented; retain the real Android WebView UA.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    loading = true; currentUrl = url.orEmpty()
                                }
                                override fun onPageFinished(view: WebView?, url: String?) { loading = false; currentUrl = url.orEmpty() }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val next = request?.url?.toString() ?: return true
                                    if (!validUrl(next)) { error = "此链接不支持在教务导入中打开"; return true }
                                    return false
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                                    if (request?.isForMainFrame == true) { loading = false; error = "教务页面暂时无法访问，请检查网络或使用学校校园网" }
                                }
                                // TLS errors use WebView's default cancellation; certificates are never bypassed.
                            }
                            webChromeClient = WebChromeClient()
                            loadUrl(url)
                        }
                    })
                }
            }
        }
    }

    private fun readTimetable() {
        val view = web ?: return
        busy = true
        readJob?.cancel()
        readJob = lifecycleScope.launch {
            try {
                val token = UUID.randomUUID().toString()
                val script = assets.open("read_timetable.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript("window.__qingkeImportToken='$token';window.__qingkeImportSystem=${org.json.JSONObject.quote(system)};window.__qingkeImportResult=null;\n$script", null)
                var raw: String? = null
                repeat(65) {
                    if (raw == null) {
                        delay(250)
                        raw = readResult(view)
                    }
                }
                val text = raw ?: throw IllegalArgumentException("读取超时，请等页面查询完成后重试")
                val parsed = withContext(Dispatchers.Default) { CourseParser.parse(text) }
                require(parsed.courses.isNotEmpty()) { "没有找到课程，请先打开学生课表并查询" }
                withContext(Dispatchers.IO) { java.io.File(cacheDir, "pending-import.json").writeText(text) }
                setResult(Activity.RESULT_OK, Intent()); finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "无法读取课表，请重试或使用文件导入" }
            finally { busy = false; view.evaluateJavascript("delete window.__qingkeImportResult;delete window.__qingkeImportToken;", null) }
        }
    }
    private suspend fun readResult(view: WebView): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript("window.__qingkeImportResult || null") { result ->
            val value = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (continuation.isActive) continuation.resume(value)
        }
    }
    private fun validUrl(url: String): Boolean {
        return SchoolConnection.valid(url, configuredUrl, allowHttp)
    }
    override fun onDestroy() {
        readJob?.cancel()
        web?.stopLoading(); web?.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web?.destroy(); web = null
        super.onDestroy()
    }
}
