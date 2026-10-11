package cn.qingke.schedule.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.*
import cn.qingke.schedule.importer.CourseParser
import cn.qingke.schedule.importer.ImportResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume

object SchoolWebSession { val mutex = Mutex() }
class SyncNeedsLogin : Exception()
class SyncTermMissing : Exception()
class SyncReadFailed : Exception()
data class SyncCapture(val result: ImportResult, val raw: String, val profile: SyncProfile)

object SchoolWebScripts {
    suspend fun evaluate(view: WebView, expression: String): Any? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(expression) { raw ->
                if (continuation.isActive) continuation.resume(runCatching { JSONTokener(raw).nextValue() }.getOrNull())
            }
        }
    }
    suspend fun login(context: Context, view: WebView, username: String, password: String, submit: Boolean): String {
        val script = context.assets.open("sync_login.js").bufferedReader().use { it.readText() }
        val options = JSONObject().put("username", username).put("password", password).put("submit", submit)
        val value = evaluate(view, "($script)($options)") as? JSONObject
        return value?.optString("state") ?: "unsupported"
    }
    suspend fun read(context: Context, view: WebView, system: String): String {
        val token = UUID.randomUUID().toString()
        val script = context.assets.open("read_timetable.js").bufferedReader().use { it.readText() }
        evaluate(view, "window.__qingkeImportToken=${JSONObject.quote(token)};window.__qingkeImportSystem=${JSONObject.quote(system)};window.__qingkeImportResult=null;\n$script")
        try {
            repeat(65) { delay(250); (evaluate(view, "window.__qingkeImportResult || null") as? String)?.let { return it } }
            throw SyncReadFailed()
        } finally { withContext(NonCancellable + Dispatchers.Main.immediate) {
            view.evaluateJavascript("delete window.__qingkeImportResult;delete window.__qingkeImportToken;", null)
        } }
    }
    fun cookies(urls: Collection<String>): Map<String, String> = urls.distinct().mapNotNull { url ->
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { url to it }
    }.toMap()
    suspend fun clearSession() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
    }
}

/** A bounded, offscreen browser, using the same parser and school-owned login scripts as import. */
class SyncBrowser(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(profile: SyncProfile): SyncCapture = withContext(Dispatchers.Main.immediate) {
        require(profile.valid())
        var mainFailure = false
        var blocked = false
        var navigating = false
        var loaded = CompletableDeferred<Unit>()
        val view = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true; settings.loadWithOverviewMode = true
            // Some school scripts check viewport geometry even without a visible Activity.
            layout(0, 0, 1080, 1600)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, icon: android.graphics.Bitmap?) { navigating = true }
                override fun onPageFinished(view: WebView?, url: String?) { navigating = false; if (!loaded.isCompleted) loaded.complete(Unit) }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val disallowed = !SyncSafety.sameOrigin(request?.url?.toString().orEmpty(), profile.loginUrl)
                    if (disallowed) { blocked = true; if (!loaded.isCompleted) loaded.complete(Unit) }; return disallowed
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) { mainFailure = true; if (!loaded.isCompleted) loaded.complete(Unit) }
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    if (request?.isForMainFrame == true) { mainFailure = true; if (!loaded.isCompleted) loaded.complete(Unit) }
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel(); mainFailure = true; if (!loaded.isCompleted) loaded.complete(Unit)
                }
            }
        }
        suspend fun navigate(url: String) {
            require(SyncSafety.sameOrigin(url, profile.loginUrl))
            mainFailure = false; blocked = false; loaded = CompletableDeferred()
            view.loadUrl(url); withTimeout(25_000) { loaded.await() }; delay(700)
            if (blocked) throw SyncNeedsLogin()
            if (mainFailure) throw SyncReadFailed()
            if (!SyncSafety.sameOrigin(view.url.orEmpty(), profile.loginUrl)) throw SyncNeedsLogin()
        }
        suspend fun controls() {
            val script = context.assets.open("sync_controls.js").bufferedReader().use { it.readText() }
            val value = SchoolWebScripts.evaluate(view, "($script)(${JSONObject(profile.controls)})") as? JSONObject
            if (value?.optString("state") != "ready") throw SyncTermMissing()
            // Generic POST/AJAX pages cannot prove that a changed term has finished rendering.
            if (value.optBoolean("changed") && profile.year.isBlank()) throw SyncTermMissing()
            if (value.optBoolean("changed")) delay(1800)
        }
        suspend fun capture(): SyncCapture {
            controls()
            val raw = SchoolWebScripts.read(context, view, profile.system)
            val root = JSONObject(raw); val source = root.optJSONObject("source") ?: throw SyncReadFailed()
            if (profile.year.isNotBlank() && (source.optString("year") != profile.year || source.optString("term") != profile.term)) throw SyncTermMissing()
            // New Zhengfang sync must use the fresh term-specific JSON, never stale rendered HTML.
            if (profile.year.isNotBlank() && !source.optBoolean("structured")) throw SyncReadFailed()
            val parsed = if (source.optBoolean("structured") && root.optJSONObject("json")?.optJSONArray("kbList")?.length() == 0)
                ImportResult(emptyList(), source = "官方课表") else withContext(Dispatchers.Default) { CourseParser.parse(raw) }
            if (parsed.warnings.isNotEmpty()) throw SyncReadFailed()
            val urls = listOf(profile.loginUrl, profile.pageUrl)
            return SyncCapture(parsed, raw, profile.copy(cookies = SchoolWebScripts.cookies(urls), pending = false))
        }
        suspend fun hasLogin(): Boolean = SchoolWebScripts.evaluate(view,
            "Array.from(document.querySelectorAll('input[type=password]')).some(e => e.getClientRects().length > 0)") == true
        try {
            withTimeout(110_000) {
                SchoolWebScripts.clearSession()
                for ((url, header) in profile.cookies) for (pair in header.split(';')) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val flags = "; Path=/; HttpOnly" + if (url.startsWith("https://", true)) "; Secure" else ""
                        CookieManager.getInstance().setCookie(url, pair.trim() + flags) { if (continuation.isActive) continuation.resume(Unit) }
                    }
                }
                navigate(profile.pageUrl)
                // Prefer an authenticated saved session, including schools requiring captcha at login.
                if (!hasLogin()) {
                    return@withTimeout capture()
                }
                navigate(profile.loginUrl)
                val state = SchoolWebScripts.login(context, view, profile.username, profile.password, true)
                if (state == "verification" || state == "unsupported") throw SyncNeedsLogin()
                if (state == "submitted") {
                    // Login may use navigation or AJAX; wait without submitting a second time.
                    var ready = false
                    repeat(40) { if (!ready) { delay(500); ready = !navigating && !hasLogin() } }
                    if (!ready) throw SyncNeedsLogin()
                }
                if (blocked || mainFailure) throw SyncNeedsLogin()
                navigate(profile.pageUrl)
                if (hasLogin()) throw SyncNeedsLogin()
                capture()
            }
        } catch (e: TimeoutCancellationException) { throw SyncReadFailed() }
        finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                view.stopLoading(); view.clearCache(true); SchoolWebScripts.clearSession(); view.destroy()
            }
        }
    }
}
