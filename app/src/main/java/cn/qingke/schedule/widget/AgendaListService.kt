package cn.qingke.schedule.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.qingke.schedule.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AgendaListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = AgendaFactory(applicationContext,
        intent.getIntExtra(DayAgendaWidget.DAY, 0).coerceIn(0, 1))
}

internal class AgendaFactory(private val context: Context, private val offset: Int) : RemoteViewsService.RemoteViewsFactory {
    @Volatile private var rows: List<AgendaRow> = emptyList()
    override fun onCreate() { /* The host calls onDataSetChanged before requesting rows. */ }
    override fun onDataSetChanged() {
        rows = runBlocking(Dispatchers.IO) {
            val source = AgendaSource.load(context)
            AgendaProjection.day(source.table, source.courses, source.slots, offset).rows
        }
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount(): Int = rows.size
    override fun getViewAt(position: Int): RemoteViews? {
        val row = rows.getOrNull(position) ?: return null
        val colors = intArrayOf(0xFF50886B.toInt(), 0xFF5A84B5.toInt(), 0xFFB99053.toInt(), 0xFFB87583.toInt(),
            0xFF9282B5.toInt(), 0xFF6B9692.toInt(), 0xFFB57C5A.toInt(), 0xFF859651.toInt())
        return RemoteViews(context.packageName, R.layout.widget_agenda_course).apply {
            setTextViewText(R.id.agenda_course_name, row.course.name)
            setTextViewText(R.id.agenda_course_time, row.time)
            setTextViewText(R.id.agenda_course_room, (if (row.ongoing) "上课中 · " else "") + row.course.room.ifBlank { "教室未填写" })
            setTextColor(R.id.agenda_course_room, Color.parseColor(if (row.ongoing) "#2F6954" else "#728077"))
            setInt(R.id.agenda_course_stripe, "setBackgroundColor", colors[Math.floorMod(row.course.color, colors.size)])
            setContentDescription(R.id.agenda_course, "${row.course.name}，${row.time}，${row.course.room}" + if (row.ongoing) "，正在上课" else "")
            setOnClickFillInIntent(R.id.agenda_course, Intent())
        }
    }
    override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_agenda_loading)
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.course?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
