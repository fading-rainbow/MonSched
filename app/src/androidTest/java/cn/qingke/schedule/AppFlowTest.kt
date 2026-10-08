package cn.qingke.schedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.qingke.schedule.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    // Accessibility clicks are deterministic even if a software emulator displays an OS dialog.
    private fun SemanticsNodeInteraction.activate(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick) { it() }
    @Test fun manualEditingDemoParityAndSettingsWork() {
        rule.waitUntil(120_000) { rule.onAllNodesWithText("你的课表，从这里开始").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("添加课程").activate()
        rule.onNode(hasSetTextAction() and hasText("课程名称 *")).performTextInput("测试高等数学")
        rule.onNode(hasSetTextAction() and hasText("教室 / 地点")).performTextInput("A201")
        rule.onNodeWithText("保存课程").activate()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("测试高等数学").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("测试高等数学").activate()
        rule.onNodeWithText("编辑", substring = false).activate()
        rule.onNode(hasSetTextAction() and hasText("课程名称 *")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("课程名称 *")).performTextInput("已编辑的数学")
        rule.onNodeWithText("保存课程").activate()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("已编辑的数学").fetchSemanticsNodes().isNotEmpty() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val dao = AppDatabase.get(context).dao()
            val table = dao.timetables().first()
            assertEquals("已编辑的数学", dao.courses(table.id).single().name)
            assertEquals("A201", dao.courses(table.id).single().room)
        }
        rule.onNodeWithText("导入", substring = false).activate()
        rule.onNodeWithText("切换学校与教务系统", substring = true).performScrollTo().activate()
        rule.onNodeWithText("学校与教务系统").assertExists()
        rule.onNode(hasSetTextAction() and hasText("学校名称")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("学校名称")).performTextInput("合成学校")
        rule.onNodeWithText("正方教务（新版／经典）").activate()
        rule.onNodeWithText("强智教务").activate()
        rule.onNode(hasSetTextAction() and hasText("官方登录网址")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("官方登录网址")).performTextInput("https://jw.example.edu/academic/login")
        rule.onNodeWithText("保存", substring = false).activate()
        rule.onNodeWithText("合成学校", substring = false).assertExists()
        val preferences = context.getSharedPreferences("preferences", 0)
        assertEquals("qiangzhi", preferences.getString("schoolSystem", null))
        assertEquals("https://jw.example.edu/academic/login", preferences.getString("schoolUrl", null))
        rule.onNodeWithText("创建示例课表，体验完整界面").performScrollTo().activate()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("示例课表 · 仅供体验，非学校真实课程").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(120_000) { rule.onAllNodesWithText("大学英语").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("下一周").activate()
        rule.onNodeWithText("大学英语").assertDoesNotExist()
        rule.onNodeWithContentDescription("上一周").activate()
        rule.onNodeWithText("大学英语").assertExists()
        rule.onNodeWithText("设置", substring = false).activate()
        rule.onNodeWithText("课表设置").assertExists()
        rule.onNodeWithText("开发者 · MonHamed").performScrollTo().assertExists()
        rule.onNodeWithText("MonSched 1.2.0").assertExists()
        rule.onNodeWithText("作息时间", substring = false).performScrollTo().activate()
        rule.onNodeWithText("初始时间仅为示例，请按学校实际作息修改。").assertExists()
        rule.onNodeWithText("取消", substring = false).activate()
        runBlocking { assertEquals(2, AppDatabase.get(context).dao().timetables().size) }
    }
}
