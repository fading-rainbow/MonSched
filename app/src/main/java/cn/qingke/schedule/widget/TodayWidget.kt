package cn.qingke.schedule.widget

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews
import cn.qingke.schedule.MainActivity
import cn.qingke.schedule.R
import cn.qingke.schedule.data.AppDatabase
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }
    companion object {
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val dao = AppDatabase.get(context).dao()
            val activeId = context.getSharedPreferences("preferences", 0).getLong("activeId", 0)
            val tables = dao.timetables(); val table = tables.firstOrNull { it.id == activeId } ?: tables.firstOrNull()
            val today = LocalDate.now(); val week = table?.currentWeek() ?: 0
            val slots = table?.let { dao.slots(it.id).associateBy { s -> s.section } }.orEmpty()
            val courses = if (table != null && table.startDateConfirmed && week in 1..table.weekCount)
                dao.courses(table.id).filter { it.weekday == today.dayOfWeek.value && it.occurs(week) } else emptyList()
            val day = listOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1]
            val title = if (table == null) "MonSched · 今日" else "MonSched · ${table.name}"
            val sub = today.format(DateTimeFormatter.ofPattern("M月d日")) + " 周$day · " +
                if (table == null) "添加课表" else if (!table.startDateConfirmed) "请设置开学日期" else if (week in 1..table.weekCount) "第 $week 周" else if (week < 1) "学期未开始" else "学期已结束"
            val text = courses.joinToString("\n\n") { c ->
                "${slots[c.startSection]?.start.orEmpty()}  ${c.name}\n${c.room.ifBlank { "教室未填写" }} · ${c.startSection}-${c.endSection}节"
            }.ifEmpty { if (table == null) "点击添加你的第一门课程" else if (!table.startDateConfirmed) "点击打开 MonSched，确认学期开学日期" else "今天没有课程，好好安排自己的时间" }
            val open = PendingIntent.getActivity(context, 2001, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_title, title); setTextViewText(R.id.widget_subtitle, sub)
                setTextViewText(R.id.widget_courses, text); setOnClickPendingIntent(R.id.widget_root, open)
            })
        }
    }
}
