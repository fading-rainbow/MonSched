package cn.qingke.schedule

import android.appwidget.*
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.qingke.schedule.data.*
import cn.qingke.schedule.widget.DayAgendaWidget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Uses a real AppWidgetHost, remote service, launcher-sized square and system alarm. */
@RunWith(AndroidJUnit4::class)
class AgendaWidgetTest {
    @Test fun compactCollectionScrollsSwitchesDayAndHidesFinishedMeetings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dao = AppDatabase.get(context).dao()
        val now = LocalDate.now()
        val tableId = runBlocking { dao.insertTimetable(Timetable(name = "组件集成验证", startDate =
            now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(), startDateConfirmed = true)) }
        context.getSharedPreferences("preferences", 0).edit().putLong("activeId", tableId).putBoolean("reminder", false).commit()
        runBlocking {
            dao.saveSlots((1..4).map { TimeSlot(tableId, it, "00:00", "23:59") })
            dao.insertCourses((1..4).map { Course(timetableId = tableId, name = "测试课程$it", room = "合成教室$it",
                weekday = now.dayOfWeek.value, startSection = it, endSection = it, weeks = "1", color = it - 1) } +
                Course(timetableId = tableId, name = "明日数学", room = "合成教室A", weekday = now.plusDays(1).dayOfWeek.value,
                    weeks = if (now.dayOfWeek == DayOfWeek.SUNDAY) "2" else "1"))
        }
        val host = AppWidgetHost(context, 8042)
        val manager = AppWidgetManager.getInstance(context)
        // Grant only on the isolated test device, never in the shipped application.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0")).use { it.readBytes() }
        var widgetId = 0
        var widget: AppWidgetHostView? = null
        fun waitFor(message: String, timeout: Long = 60_000, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < deadline) {
                var passed = false
                instrumentation.runOnMainSync { passed = predicate() }
                if (passed) return
                SystemClock.sleep(250)
            }
            fail(message)
        }
        fun visibleTexts(view: View?): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
            else -> emptyList()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    host.startListening()
                    widgetId = host.allocateAppWidgetId()
                    assertTrue("Widget binding permission missing", manager.bindAppWidgetIdIfAllowed(widgetId,
                        ComponentName(context, DayAgendaWidget::class.java)))
                    widget = host.createView(activity, widgetId, manager.getAppWidgetInfo(widgetId))
                    val size = (160 * activity.resources.displayMetrics.density).toInt()
                    val container = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        setBackgroundColor(Color.rgb(227, 234, 221))
                        addView(TextView(activity).apply { text = "2×2 组件 · 合成验证课程"; textSize = 16f; setTextColor(Color.DKGRAY) })
                        addView(widget, LinearLayout.LayoutParams(size, size))
                    }
                    activity.setContentView(container)
                    widget!!.updateAppWidgetSize(Bundle(), 160, 160, 160, 160)
                }
                runBlocking { DayAgendaWidget.refresh(context) }
                waitFor("Collection failed to load four real remote rows") {
                    val list = widget?.findViewById<ListView>(R.id.agenda_list)
                    list?.count == 4 && visibleTexts(widget).contains("测试课程1")
                }
                instrumentation.runOnMainSync {
                    val list = widget!!.findViewById<ListView>(R.id.agenda_list)
                    assertTrue("Four course cards should require scrolling in a 2×2 square", list.childCount < 4)
                    list.setSelection(3)
                }
                waitFor("Last course cannot be reached by scrolling") { visibleTexts(widget).contains("测试课程4") }
                instrumentation.runOnMainSync { widget!!.findViewById<TextView>(R.id.agenda_tomorrow).performClick() }
                waitFor("Tomorrow switch did not replace the collection") {
                    widget?.findViewById<ListView>(R.id.agenda_list)?.count == 1 && visibleTexts(widget).contains("明日数学")
                }
                instrumentation.runOnMainSync { widget!!.findViewById<TextView>(R.id.agenda_today).performClick() }
                waitFor("Today switch did not restore its collection") { widget?.findViewById<ListView>(R.id.agenda_list)?.count == 4 }
                // A real scheduled end boundary must update the widget with reminders disabled.
                val end = LocalTime.now().plusMinutes(2).withSecond(0).withNano(0)
                assertTrue("Run this test before 23:57", end > LocalTime.now())
                runBlocking { dao.saveSlots(listOf(TimeSlot(tableId, 1, "00:00", end.format(DateTimeFormatter.ofPattern("HH:mm")))))
                    DayAgendaWidget.refresh(context) }
                instrumentation.runOnMainSync { widget!!.findViewById<ListView>(R.id.agenda_list).setSelection(0) }
                waitFor("First card absent before its end time") { visibleTexts(widget).contains("测试课程1") }
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    File(context.getExternalFilesDir(null), "monsched-widget-test.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                waitFor("System end alarm failed to hide a completed course", 150_000) {
                    widget?.findViewById<ListView>(R.id.agenda_list)?.count == 3
                }
                runBlocking { assertEquals("Hiding a card must preserve the stored meeting", 5, dao.courses(tableId).size) }
                assertFalse(context.getSharedPreferences("preferences", 0).getBoolean("reminder", true))
            } finally {
                instrumentation.runOnMainSync { host.deleteHost(); host.stopListening() }
                runBlocking { dao.timetables().firstOrNull { it.id == tableId }?.let { dao.deleteTimetable(it) } }
            }
        }
    }
}
