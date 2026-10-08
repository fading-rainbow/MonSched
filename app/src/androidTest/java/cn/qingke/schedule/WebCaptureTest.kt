package cn.qingke.schedule

import android.annotation.SuppressLint
import android.webkit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.qingke.schedule.importer.CourseParser
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebCaptureTest {
    @SuppressLint("SetJavaScriptEnabled")
    private fun capture(html: String, url: String, system: String): org.json.JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loaded = CountDownLatch(1); val captured = CountDownLatch(1)
        lateinit var web: WebView
        var result: String? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            web = WebView(activity).apply {
                settings.javaScriptEnabled = true; settings.allowFileAccess = false
                webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() } }
            }
            activity.setContentView(web)
            web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
        }
        try {
            assertTrue(loaded.await(120, TimeUnit.SECONDS))
            val script = context.assets.open("read_timetable.js").bufferedReader().use { it.readText() }
            instrumentation.runOnMainSync {
                web.evaluateJavascript("window.__qingkeImportToken='test';window.__qingkeImportSystem='${system}';$script", null)
                web.postDelayed({
                    web.evaluateJavascript("JSON.stringify({capture:window.__qingkeImportResult,body:window.__posted,url:window.__requested})") { raw ->
                        result = JSONTokener(raw).nextValue() as? String; captured.countDown()
                    }
                }, 1500)
            }
            assertTrue(captured.await(120, TimeUnit.SECONDS))
            return org.json.JSONObject(result!!)
        } finally { instrumentation.runOnMainSync { web.destroy() }; scenario.close() }
    }

    @Test fun captureUsesSelectedTermAndCustomSchoolContextWithoutLoginFields() {
        val html = """<html><body><input id="password" value="TEST_SECRET_DO_NOT_CAPTURE">
            <select id="xnm"><option value="2026">2026</option></select><select id="xqm"><option value="3">秋季</option></select>
            <table id="kbgrid_table_0"><tr><th>节次</th><th>周一</th></tr><tr><td>第1节</td><td><div class="timetable_con"><b>测试数学</b><p>(1-2节)1-16周</p></div><input value="TEST_SECRET_DO_NOT_CAPTURE"></td></tr></table>
            <script>window.fetch=function(url,options){window.__posted=options.body;window.__requested=url;return Promise.resolve({ok:true,json:function(){return Promise.resolve({kbList:[{kcmc:'测试数学',xm:'李老师',cdmc:'A201',xqj:'1',jcs:'1-2',zcd:'1-16周(单)'}]});}});};</script>
            </body></html>"""
        val outer = capture(html,"https://jw.example.edu/academic/kbcx/xskbcx_cxXskbcxIndex.html","zhengfang")
        val text = outer.getString("capture")
        assertFalse(text.contains("TEST_SECRET_DO_NOT_CAPTURE"))
        assertTrue(outer.getString("body").contains("xnm=2026"))
        assertTrue(outer.getString("body").contains("xqm=3"))
        assertEquals("https://jw.example.edu/academic/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151", outer.getString("url"))
        val course = CourseParser.parse(text).courses.single()
        assertEquals("李老师", course.teacher)
        assertEquals((1..16).filter { it % 2 == 1 }.toSet(), course.weekSet())
    }

    @Test fun genericCaptureReadsSameOriginFrameAndNeverCallsZhengfangApi() {
        val html = """<html><body><input value="TEST_SECRET_DO_NOT_CAPTURE">
            <script>window.fetch=function(){window.__posted='UNEXPECTED_API_CALL';throw new Error('Unexpected POST');};</script>
            <iframe srcdoc="&lt;table&gt;&lt;tr&gt;&lt;th&gt;CourseName&lt;/th&gt;&lt;th&gt;Day&lt;/th&gt;&lt;th&gt;Sections&lt;/th&gt;&lt;th&gt;Weeks&lt;/th&gt;&lt;/tr&gt;&lt;tr&gt;&lt;td&gt;FrameMath&lt;/td&gt;&lt;td&gt;Tuesday&lt;/td&gt;&lt;td&gt;3-4&lt;/td&gt;&lt;td&gt;1-16&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;"></iframe>
            </body></html>"""
        val outer = capture(html,"https://jw.example.edu/portal/timetable","generic")
        assertFalse(outer.has("body"))
        assertFalse(outer.getString("capture").contains("TEST_SECRET_DO_NOT_CAPTURE"))
        val course=CourseParser.parse(outer.getString("capture")).courses.single()
        assertEquals("FrameMath",course.name);assertEquals(2,course.weekday);assertEquals(3,course.startSection)
    }
}
