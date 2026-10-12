package cn.qingke.schedule

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.*
import cn.qingke.schedule.sync.*
import kotlinx.coroutines.runBlocking
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncOptInTest {
    @get:Rule val rule = createEmptyComposeRule()
    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun findWeb(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).asSequence().mapNotNull { findWeb(group.getChildAt(it)) }.firstOrNull()
    }
    private fun js(scenario: ActivityScenario<WebImportActivity>, script: String): Any? {
        val latch = CountDownLatch(1); var value: Any? = null
        scenario.onActivity { activity ->
            findWeb(activity.window.decorView)!!.evaluateJavascript(script) { raw ->
                value = runCatching { JSONTokener(raw).nextValue() }.getOrNull(); latch.countDown()
            }
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS)); return value
    }
    private fun readPortal(remember: Boolean): Pair<Intent, Long> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.get(context).dao()
        rule.waitUntil(30_000) { !SchoolWebSession.mutex.isLocked }
        val id = runBlocking { dao.insertTimetable(Timetable(name = "首次同步合成测试")) }
        SyntheticSchool().use { school ->
            val intent = Intent(context, WebImportActivity::class.java).putExtra("url", school.loginUrl).putExtra("system", "zhengfang")
                .putExtra("allowHttp", true).putExtra("timetableId", id).putExtra("configureSync", remember).putExtra("schoolName", "合成学校")
            val scenario = ActivityScenario.launchActivityForResult<WebImportActivity>(intent)
            try {
                if (remember) {
                    rule.onNode(hasSetTextAction() and hasText("教务账号")).performTextInput("demo")
                    rule.onNode(hasSetTextAction() and hasText("教务密码")).performTextInput("SYNTHETIC_PASSWORD")
                    rule.onNodeWithText("本机记住并同步").activate()
                    rule.waitUntil(120_000) { js(scenario, "document.querySelector('#yhm') && document.querySelector('#yhm').value") == "demo" }
                    assertEquals("SYNTHETIC_PASSWORD", js(scenario, "document.querySelector('#mm').value"))
                } else {
                    rule.onNode(isToggleable()).assertIsOff()
                    rule.waitUntil(120_000) { js(scenario, "!!document.querySelector('#yhm')") == true }
                    js(scenario, "document.querySelector('#yhm').value='demo';document.querySelector('#mm').value='SYNTHETIC_PASSWORD';")
                }
                js(scenario, "document.querySelector('button').click()")
                rule.waitUntil(120_000) { js(scenario, "location.pathname") == "/academic/xtgl/index_initMenu.html" }
                rule.onNodeWithText("课表入口").activate()
                rule.waitUntil(120_000) { js(scenario, "!!document.querySelector('#xnm')") == true }
                js(scenario, "document.querySelector('#xnm').value='2026';document.querySelector('#xqm').value='3';")
                rule.onNodeWithText("读取课表").activate()
                val result = scenario.result
                assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
                return result.resultData to id
            } finally { scenario.close() }
        }
    }
    @Test fun normalImportDoesNotRememberCredentialsOrEnableSync() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (result, id) = readPortal(false)
        try {
            assertNull(result.getStringExtra("syncToken"))
            runBlocking { assertNull(AppDatabase.get(context).dao().syncConnection(id)) }
        } finally {
            File(context.cacheDir, "pending-import.json").delete()
            runBlocking { val dao = AppDatabase.get(context).dao(); dao.timetables().firstOrNull { it.id == id }?.let { dao.deleteTimetable(it) } }
        }
    }
    @Test fun optedInLoginStagesEncryptedRecipeAndOnlyConfirmationEnablesSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val (result, id) = readPortal(true)
        val token = result.getStringExtra("syncToken")!!; val vault = SyncVault(context)
        val profile = vault.load(token)!!
        val dao = AppDatabase.get(context).dao(); val store = ViewModelStore()
        val oldActive = context.getSharedPreferences("preferences", 0).getLong("activeId", 0)
        try {
            assertEquals("2026", profile.year); assertEquals("3", profile.term)
            assertEquals(mapOf("xnm" to "2026", "xqm" to "3"), profile.controls)
            assertTrue(profile.cookies.isNotEmpty()); assertTrue(profile.pending)
            runBlocking { assertNull(dao.syncConnection(id)) }
            val file = File(context.cacheDir, "pending-import.json")
            val parsed = CourseParser.parse(file.readText())
            assertFalse(file.readText().contains("SYNTHETIC_PASSWORD"))
            // An old user already has the same official course, plus a personal course.
            runBlocking {
                dao.insertCourses(listOf(parsed.courses.single().copy(timetableId = id, note = "旧课表笔记", color = 5),
                    Course(timetableId = id, name = "自建自习", note = "必须保留")))
            }
            val legacyId = runBlocking { dao.courses(id).single { it.name != "自建自习" }.id }
            val model = AppViewModel(context.applicationContext as Application)
            store.put("sync-confirmation", model)
            instrumentation.runOnMainSync { model.switch(id) }
            rule.waitUntil(120_000) { model.active.value?.id == id }
            val imported = CountDownLatch(1)
            instrumentation.runOnMainSync { model.importCourses(parsed.courses, false, token) { imported.countDown() } }
            assertTrue(imported.await(120, TimeUnit.SECONDS))
            runBlocking {
                val connection = dao.syncConnection(id)!!
                assertTrue(connection.enabled); assertEquals(token, connection.sourceId)
                val courses = dao.courses(id)
                assertEquals(2, courses.size)
                val bound = courses.single { it.name != "自建自习" }
                assertEquals(token, bound.syncSource); assertEquals(legacyId, bound.id)
                assertEquals("旧课表笔记", bound.note); assertEquals(5, bound.color)
                assertEquals("必须保留", courses.single { it.name == "自建自习" }.note)
            }
            assertFalse(vault.load(token)!!.pending)
            val again = profile.copy(id = java.util.UUID.randomUUID().toString(), pending = true)
            vault.save(again)
            val importedAgain = CountDownLatch(1)
            instrumentation.runOnMainSync { model.importCourses(parsed.courses, false, again.id) { importedAgain.countDown() } }
            assertTrue(importedAgain.await(120, TimeUnit.SECONDS))
            runBlocking {
                assertEquals(2, dao.courses(id).size)
                val bound = dao.courses(id).single { it.name != "自建自习" }
                assertEquals(legacyId, bound.id); assertEquals(again.id, bound.syncSource)
                assertEquals("旧课表笔记", bound.note)
            }
            assertNull(vault.load(token))
        } finally {
            runBlocking { SyncCoordinator.forget(context, id); dao.timetables().firstOrNull { it.id == id }?.let { dao.deleteTimetable(it) } }
            vault.delete(token); File(context.cacheDir, "pending-import.json").delete()
            instrumentation.runOnMainSync { store.clear() }
            context.getSharedPreferences("preferences", 0).edit().putLong("activeId", oldActive).commit()
        }
    }
}
