package cn.qingke.schedule

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TimePicker
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import cn.qingke.schedule.data.*
import cn.qingke.schedule.reminders.ReminderPlanner
import cn.qingke.schedule.widget.AgendaProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ScheduleRepairTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun picker(view: View): TimePicker? = if (view is TimePicker) view else (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).asSequence().mapNotNull { picker(group.getChildAt(it)) }.firstOrNull()
    }
    private fun pick(hour: Int, minute: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.waitUntil(60_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = WindowInspector.getGlobalWindowViews().any { picker(it) != null } }
            ready
        }
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().first { picker(it) != null }
            val time = picker(root)!!
            time.hour = hour; time.minute = minute
            assertTrue(root.findViewById<Button>(android.R.id.button1).performClick())
        }
    }

    @Test fun existingDuplicatesMergeWithoutLosingNotesAndCustomTimesReachEveryConsumer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val dao = AppDatabase.get(context).dao()
        rule.waitUntil(120_000) { rule.onAllNodesWithText("你的课表，从这里开始").fetchSemanticsNodes().isNotEmpty() }
        val table = runBlocking { dao.timetables().first() }.copy(startDateConfirmed = true, sectionCount = 4,
            startDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString())
        val source = UUID.randomUUID().toString()
        var otherId = 0L
        runBlocking {
            dao.updateTimetable(table)
            dao.saveSyncConnection(SyncConnection(table.id, source, "合成学校", enabled = false, status = "paused"))
            val c = Course(timetableId = table.id, name = "数学", teacher = "教师", room = "A101", weeks = "1", note = "旧备注", color = 4)
            dao.insertCourses(listOf(c, c.copy(syncSource = source, note = "新备注", color = 0),
                c.copy(name = "英语", note = "真正重叠保留"), c.copy(weeks = "2", note = "不同周保留")))
            otherId = dao.insertTimetable(Timetable(name = "另一学校", startDateConfirmed = true))
            dao.saveSlots(listOf(TimeSlot(otherId, 1, "09:00", "09:45")))
            dao.insertCourses(listOf(c.copy(timetableId = otherId, note = "另一学校保留")))
        }
        val oldId = runBlocking { dao.courses(table.id).first().id }
        rule.waitUntil(120_000) { rule.onAllNodesWithText("发现 1 条完全重复记录 · 点此整理").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("发现 1 条完全重复记录 · 点此整理").activate()
        rule.onNodeWithText("数学 · 2 条 → 1 条").assertExists()
        rule.onNodeWithText("合并 1 条重复记录").activate()
        rule.waitUntil(120_000) { runBlocking { dao.courses(table.id).size == 3 } }
        runBlocking {
            val kept = dao.courses(table.id).single { it.name == "数学" && it.weeks == "1" }
            assertEquals(oldId, kept.id); assertEquals(4, kept.color); assertEquals(source, kept.syncSource)
            assertEquals("旧备注\n\n新备注", kept.note)
            assertEquals("真正重叠保留", dao.courses(table.id).single { it.name == "英语" }.note)
            assertEquals("另一学校保留", dao.courses(otherId).single().note)
        }
        rule.onNodeWithContentDescription("设置第1节作息时间").activate()
        rule.onNodeWithContentDescription("第1节开始时间").activate(); pick(8, 10)
        rule.onNodeWithContentDescription("第1节结束时间").activate(); pick(8, 5)
        rule.onNodeWithText("保存作息").activate()
        rule.onNodeWithText("每节的结束时间必须晚于开始时间").assertExists()
        runBlocking { assertEquals("08:00", dao.slots(table.id).first().start) }
        rule.onNodeWithContentDescription("第1节结束时间").activate(); pick(8, 50)
        rule.onNodeWithText("保存作息").activate()
        rule.waitUntil(120_000) { runBlocking { dao.slots(table.id).first().start == "08:10" } }
        rule.onNodeWithContentDescription("设置第1节作息时间").assertExists()
        rule.onNodeWithText("08:10").assertExists(); rule.onNodeWithText("08:50").assertExists()
        runBlocking {
            val courses = dao.courses(table.id); val slots = dao.slots(table.id); val zone = ZoneId.systemDefault()
            val now = table.monday(1).atTime(8, 20).atZone(zone).toInstant()
            val agenda = AgendaProjection.day(table, courses, slots, 0, now, zone)
            assertEquals(2, agenda.rows.size); assertTrue(agenda.rows.all { it.time == "08:10–09:40" && it.ongoing })
            val reminder = ReminderPlanner.next(table, courses, slots, 10, table.monday(1).atTime(7,0).atZone(zone).toInstant(), zone)!!
            assertEquals(table.monday(1).atTime(8,10).atZone(zone).toInstant().toEpochMilli(), reminder.startAt)
            assertEquals("09:00", dao.slots(otherId).single().start)
            assertEquals(3, dao.courses(table.id).size)
            dao.deleteSyncConnection(table.id)
            dao.deleteTimetable(dao.timetables().first { it.id == otherId })
        }
    }
}
