package cn.qingke.schedule.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import cn.qingke.schedule.MainActivity
import cn.qingke.schedule.R
import cn.qingke.schedule.data.*
import cn.qingke.schedule.widget.TodayWidget
import cn.qingke.schedule.widget.DayAgendaWidget
import cn.qingke.schedule.sync.SyncScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

data class NextReminder(val course: Course, val startAt: Long, val notifyAt: Long)

object ReminderPlanner {
    fun next(timetable: Timetable, courses: List<Course>, slots: List<TimeSlot>, lead: Int,
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): NextReminder? {
        val starts = slots.associate { it.section to it.start }
        return courses.flatMap { c -> c.weekSet().filter { it in 1..timetable.weekCount }.mapNotNull { week ->
            val time = starts[c.startSection] ?: return@mapNotNull null
            val date = timetable.monday(week).plusDays((c.weekday - 1).toLong())
            val start = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
            val notify = start.minusSeconds(lead * 60L)
            if (notify > now) NextReminder(c, start.toEpochMilli(), notify.toEpochMilli()) else null
        } }.minByOrNull { it.notifyAt }
    }
}

object Reminders {
    const val CHANNEL = "class_reminders"
    private val mutex = Mutex()
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "上课提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "上课前提醒课程和教室" })
    }
    suspend fun schedule(context: Context) = mutex.withLock {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java)
        val previous = PendingIntent.getBroadcast(context, 1001, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (previous != null) { alarms.cancel(previous); previous.cancel() }
        val prefs = context.getSharedPreferences("preferences", 0)
        if (prefs.getBoolean("reminder", false)) {
            val dao = AppDatabase.get(context).dao()
            val tables = dao.timetables()
            val table = tables.firstOrNull { it.id == prefs.getLong("activeId", 0) } ?: tables.firstOrNull()
            if (table != null && table.startDateConfirmed) {
                val next = ReminderPlanner.next(table, dao.courses(table.id), dao.slots(table.id), prefs.getInt("leadMinutes", 10))
                if (next != null) {
                    intent.putExtra("courseId", next.course.id).putExtra("timetableId", table.id).putExtra("startAt", next.startAt)
                    val pending = PendingIntent.getBroadcast(context, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    // Avoid requesting special exact-alarm access for the first version.
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.notifyAt, pending)
                }
            }
        }
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val refresh = PendingIntent.getBroadcast(context, 1002,
            Intent(context, RescheduleReceiver::class.java).setAction("cn.qingke.schedule.MIDNIGHT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, refresh)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("preferences", 0)
                val tableId = intent.getLongExtra("timetableId", -1)
                val startAt = intent.getLongExtra("startAt", 0)
                val dao = AppDatabase.get(context).dao()
                val course = dao.courses(tableId).firstOrNull { it.id == intent.getLongExtra("courseId", -1) }
                val table = dao.timetables().firstOrNull { it.id == tableId }
                val date = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toLocalDate()
                val valid = course != null && table != null && course.occurs(table.currentWeek(date)) && course.weekday == date.dayOfWeek.value
                    && prefs.getBoolean("reminder", false) && prefs.getLong("activeId", 0) == tableId
                    && System.currentTimeMillis() < startAt + 15 * 60_000
                val permitted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (valid && permitted && course != null) {
                    val open = PendingIntent.getActivity(context, 1003, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val time = Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()).toLocalTime().toString()
                    val notification = Notification.Builder(context, Reminders.CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle("$time · ${course.name}")
                        .setContentText("${course.room.ifBlank { "教室未填写" }} · 第 ${course.startSection}-${course.endSection} 节")
                        .setContentIntent(open).setAutoCancel(true).setShowWhen(true).build()
                    context.getSystemService(NotificationManager::class.java).notify(3001, notification)
                }
                Reminders.schedule(context); TodayWidget.refresh(context); DayAgendaWidget.refresh(context)
            } finally { pending.finish() }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, "cn.qingke.schedule.MIDNIGHT")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Reminders.schedule(context); TodayWidget.refresh(context); DayAgendaWidget.refresh(context)
                SyncScheduler.maintain(context, intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
            } finally { pending.finish() }
        }
    }
}
