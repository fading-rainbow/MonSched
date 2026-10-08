package cn.qingke.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import cn.qingke.schedule.MainActivity
import cn.qingke.schedule.R
import cn.qingke.schedule.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

internal data class AgendaSource(val table: Timetable?, val courses: List<Course>, val slots: List<TimeSlot>) {
    companion object {
        suspend fun load(context: Context): AgendaSource {
            val dao = AppDatabase.get(context).dao()
            val selected = context.getSharedPreferences("preferences", 0).getLong("activeId", 0)
            val tables = dao.timetables()
            val table = tables.firstOrNull { it.id == selected } ?: tables.firstOrNull()
            return AgendaSource(table, table?.let { dao.courses(it.id) }.orEmpty(), table?.let { dao.slots(it.id) }.orEmpty())
        }
    }
}

class DayAgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAsync(context)
    override fun onEnabled(context: Context) = updateAsync(context)
    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences(PREFS, 0).edit().apply { ids.forEach { remove("day_$it") } }.apply()
        updateAsync(context)
    }
    override fun onDisabled(context: Context) { cancelAlarm(context) }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SELECT_DAY -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id !in widgetIds(context)) return
                val offset = intent.getIntExtra(DAY, 0).coerceIn(0, 1)
                context.getSharedPreferences(PREFS, 0).edit().putInt("day_$id", offset).apply()
                updateAsync(context)
            }
            REFRESH -> updateAsync(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun updateAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }

    companion object {
        const val DAY = "agenda_day"
        private const val PREFS = "agenda_widgets"
        private const val SELECT_DAY = "cn.qingke.schedule.widget.SELECT_DAY"
        private const val REFRESH = "cn.qingke.schedule.widget.REFRESH"
        private val mutex = Mutex()
        internal fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, DayAgendaWidget::class.java))

        fun preciseUpdatesAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        @Suppress("DEPRECATION")
        suspend fun refresh(context: Context) = mutex.withLock {
            val ids = widgetIds(context)
            if (ids.isEmpty()) { cancelAlarm(context); return@withLock }
            val source = AgendaSource.load(context)
            val now = Instant.now()
            val manager = AppWidgetManager.getInstance(context)
            val prefs = context.getSharedPreferences(PREFS, 0)
            for (id in ids) {
                val offset = prefs.getInt("day_$id", 0).coerceIn(0, 1)
                val day = AgendaProjection.day(source.table, source.courses, source.slots, offset, now)
                val views = RemoteViews(context.packageName, R.layout.widget_agenda)
                views.setTextViewText(R.id.agenda_date, day.subtitle)
                views.setTextViewText(R.id.agenda_empty, day.emptyMessage)
                views.setViewVisibility(R.id.agenda_empty, if (day.rows.isEmpty()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.agenda_list, if (day.rows.isEmpty()) View.GONE else View.VISIBLE)
                views.setContentDescription(R.id.agenda_date, "MonSched · ${source.table?.name.orEmpty()} · ${day.subtitle} · ${day.rows.size}门课程")
                for ((button, buttonOffset) in listOf(R.id.agenda_today to 0, R.id.agenda_tomorrow to 1)) {
                    views.setInt(button, "setBackgroundResource", if (offset == buttonOffset) R.drawable.agenda_tab_selected else R.drawable.agenda_tab_unselected)
                    views.setTextColor(button, Color.parseColor(if (offset == buttonOffset) "#FFFFFF" else "#476555"))
                    views.setOnClickPendingIntent(button, PendingIntent.getBroadcast(context, 0,
                        Intent(context, DayAgendaWidget::class.java).setAction(SELECT_DAY)
                            .setData(Uri.parse("monsched://agenda/$id/day/$buttonOffset"))
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(DAY, buttonOffset),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                views.setRemoteAdapter(R.id.agenda_list, Intent(context, AgendaListService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(DAY, offset)
                    .setData(Uri.parse("monsched://agenda/$id/list/$offset")))
                val openIntent = Intent(context, MainActivity::class.java)
                val open = PendingIntent.getActivity(context, 3101, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.agenda_date, open)
                views.setOnClickPendingIntent(R.id.agenda_empty, open)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                views.setPendingIntentTemplate(R.id.agenda_list, PendingIntent.getActivity(context, 3103, openIntent, flags))
                views.setOnClickPendingIntent(R.id.agenda_refresh, PendingIntent.getBroadcast(context, 0,
                    Intent(context, DayAgendaWidget::class.java).setAction(REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, views)
            }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.agenda_list)
            schedule(context, AgendaProjection.nextRefresh(source.table, source.courses, source.slots, now).toEpochMilli())
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 3102,
            Intent(context, DayAgendaWidget::class.java).setAction(REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }

        private fun schedule(context: Context, at: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = alarmIntent(context)
            if (preciseUpdatesAllowed(context)) {
                try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); return }
                catch (_: SecurityException) { /* Access may have changed since the check. */ }
            }
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}
