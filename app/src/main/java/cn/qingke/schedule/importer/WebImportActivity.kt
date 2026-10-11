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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import cn.qingke.schedule.ui.QingKeTheme
import kotlinx.coroutines.*
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume
import cn.qingke.schedule.data.AppDatabase
import cn.qingke.schedule.sync.*

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
    private var ownsSession = false
    private var syncOptIn by mutableStateOf(false)
    private var syncDialog by mutableStateOf(false)
    private var syncUsername by mutableStateOf("")
    private var syncPassword by mutableStateOf("")
    private var filledUrl = ""
    private var targetId = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SchoolWebSession.mutex.tryLock()) {
            android.widget.Toast.makeText(this, "正在同步官方课表，请稍后再打开教务页面", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        ownsSession = true
        enableEdgeToEdge()
        val url = intent.getStringExtra("url") ?: SchoolConnection.QIT_URL
        configuredUrl = url
        system = intent.getStringExtra("system") ?: "zhengfang"
        allowHttp = intent.getBooleanExtra("allowHttp", false)
        targetId = intent.getLongExtra("timetableId", 0)
        syncDialog = intent.getBooleanExtra("configureSync", false)
        if (syncDialog && targetId > 0) lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                AppDatabase.get(this@WebImportActivity).dao().syncConnection(targetId)?.let {
                    runCatching { SyncVault(this@WebImportActivity).load(it.sourceId) }.getOrNull()
                }
            }
            if (profile != null && profile.loginUrl == configuredUrl) { syncUsername = profile.username; syncPassword = profile.password }
        }
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
                        if (targetId > 0) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("记住登录并每日同步", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(syncOptIn, { if (it) syncDialog = true else { syncOptIn = false; syncPassword = ""; filledUrl = "" } })
                            if (syncOptIn) TextButton(onClick = { syncDialog = true }) { Text("修改") }
                        }
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
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false; currentUrl = url.orEmpty()
                                    prefillLogin()
                                }
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
                if (syncDialog) AlertDialog(onDismissRequest = { syncDialog = false }, title = { Text("记住登录与每日同步") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("填写本次登录的账号密码，自动填入同一官方入口。成功读取并确认导入后，才会加密保存在这台手机。")
                        OutlinedTextField(syncUsername, { syncUsername = it.take(256) }, label = { Text("教务账号") }, singleLine = true)
                        OutlinedTextField(syncPassword, { syncPassword = it.take(1024) }, label = { Text("教务密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Text("记住所选学期和课表页面，每天 0 点后检查调课。省电限制可能延迟；验证码或统一认证可能需要重新登录。可随时暂停或清除。", style = MaterialTheme.typography.bodySmall)
                        if (Uri.parse(configuredUrl).scheme == "http") Text("该学校入口使用 HTTP，登录信息在网络上传输时未加密，请仅在可信网络使用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } }, confirmButton = { TextButton(onClick = { syncOptIn = true; syncDialog = false; filledUrl = ""; prefillLogin() },
                        enabled = syncUsername.isNotBlank() && syncPassword.isNotEmpty()) { Text("本机记住并同步") } },
                    dismissButton = { TextButton(onClick = { syncDialog = false }) { Text("取消") } })
            }
        }
    }

    private fun prefillLogin() {
        val view = web ?: return
        val url = view.url.orEmpty()
        if (!syncOptIn || !SyncSafety.sameOrigin(url, configuredUrl) || filledUrl == url) return
        lifecycleScope.launch {
            if (SchoolWebScripts.login(this@WebImportActivity, view, syncUsername, syncPassword, false) == "filled") filledUrl = url
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
                val result = Intent()
                if (syncOptIn) {
                    require(parsed.warnings.isEmpty()) { "有课程未完整识别，暂不能开启同步，请检查后再读或使用普通导入" }
                    val source = org.json.JSONObject(text).optJSONObject("source")
                    require(source?.optString("year").isNullOrBlank() || source?.optBoolean("structured") == true) {
                        "官方结构化课表读取未完成，暂不能开启同步，请重新查询或使用普通导入"
                    }
                    val detected = source?.optString("pageUrl").orEmpty()
                    val pageUrl = detected.takeIf { SyncSafety.sameOrigin(it, configuredUrl) } ?: view.url.orEmpty()
                    require(SyncSafety.sameOrigin(pageUrl, configuredUrl)) { "课表与登录不在同一官方入口，暂不能记住自动登录，请使用普通导入" }
                    val controlObj = source?.optJSONObject("controls")
                    val controls = controlObj?.keys()?.asSequence()?.associateWith { controlObj.getString(it) }.orEmpty()
                    val profile = SyncProfile(loginUrl = configuredUrl, pageUrl = pageUrl, system = system, allowHttp = allowHttp,
                        username = syncUsername.trim(), password = syncPassword, controls = controls,
                        cookies = SchoolWebScripts.cookies(listOf(configuredUrl, pageUrl)), year = source?.optString("year").orEmpty(),
                        term = source?.optString("term").orEmpty(), timetableId = targetId, schoolName = intent.getStringExtra("schoolName").orEmpty())
                    withContext(Dispatchers.IO) { SyncVault(this@WebImportActivity).save(profile) }
                    result.putExtra("syncToken", profile.id)
                }
                withContext(Dispatchers.IO) { java.io.File(cacheDir, "pending-import.json").writeText(text) }
                setResult(Activity.RESULT_OK, result); finish()
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
        if (ownsSession) {
            web?.stopLoading(); web?.clearCache(true); web?.destroy(); web = null
            // Complete cookie removal before letting a background sync restore its own session.
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData(); SchoolWebSession.mutex.unlock()
            }
            ownsSession = false
        }
        syncPassword = ""
        super.onDestroy()
    }
}
