package cn.qingke.schedule.sync

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import androidx.work.*
import cn.qingke.schedule.MainActivity
import cn.qingke.schedule.R
import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.CourseParser
import cn.qingke.schedule.reminders.Reminders
import cn.qingke.schedule.widget.DayAgendaWidget
import cn.qingke.schedule.widget.TodayWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

object SyncSnapshots {
    fun encode(courses: List<Course>): String = JSONObject().put("courses", JSONArray().apply {
        courses.forEach { c -> put(JSONObject().put("name", c.name).put("teacher", c.teacher).put("room", c.room)
            .put("weekday", c.weekday).put("startSection", c.startSection).put("endSection", c.endSection)
            .put("weeks", c.weeks).put("color", c.color).put("note", c.note)) }
    }).toString()
    fun decode(value: String): List<Course> = CourseParser.parseJson(value).also {
        require(it.warnings.isEmpty()) { "同步结果不完整，请重新登录读取" }
    }.courses
}

object SyncCoordinator {
    val mutations = Mutex()
    suspend fun apply(context: Context, connection: SyncConnection, courses: List<Course>, approve: Boolean = false,
        profile: SyncProfile? = null): SyncDelta? = mutations.withLock {
        val db = AppDatabase.get(context); val dao = db.dao()
        db.withTransaction {
            val current = dao.syncConnection(connection.timetableId)
            if (current?.sourceId != connection.sourceId || !current.enabled) return@withTransaction null
            if (profile != null) {
                require(profile.id == current.sourceId)
                withContext(Dispatchers.IO) { SyncVault(context).save(profile) }
            }
            val table = dao.timetables().firstOrNull { it.id == current.timetableId } ?: return@withTransaction null
            val delta = SyncReconciler.plan(dao.courses(table.id), courses, current.sourceId, table.id)
            val now = System.currentTimeMillis()
            if (delta.review && !approve) {
                dao.saveSyncConnection(current.copy(status = "review", lastAttempt = now,
                    message = "官方课程为空或数量明显减少，请确认后应用；原课表已保留", pendingSnapshot = SyncSnapshots.encode(courses)))
            } else {
                if (delta.removed.isNotEmpty()) dao.deleteCourseIds(delta.removed.map { it.id })
                for (course in delta.updates) dao.saveCourse(course)
                dao.updateTimetable(table.copy(sectionCount = maxOf(table.sectionCount, courses.maxOfOrNull { it.endSection } ?: 1),
                    weekCount = maxOf(table.weekCount, courses.flatMap { it.weekSet() }.maxOrNull() ?: 1)))
                dao.saveSyncConnection(current.copy(status = "ready", lastAttempt = now, lastSuccess = now,
                    lastChanged = if (delta.added + delta.changed + delta.removed.size > 0) now else current.lastChanged,
                    message = delta.summary, pendingSnapshot = ""))
            }
            delta
        }
    }
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        Reminders.schedule(context); TodayWidget.refresh(context); DayAgendaWidget.refresh(context)
    }
    suspend fun forget(context: Context, tableId: Long) = mutations.withLock {
        val db = AppDatabase.get(context); val dao = db.dao()
        val connection = dao.syncConnection(tableId) ?: return@withLock
        SyncScheduler.cancel(context, connection.sourceId)
        db.withTransaction { dao.detachSyncCourses(connection.sourceId); dao.deleteSyncConnection(tableId) }
        withContext(Dispatchers.IO) { SyncVault(context).delete(connection.sourceId) }
    }
}

object SyncScheduler {
    private fun work(context: Context, connection: SyncConnection, force: Boolean, delay: Long, name: String) {
        val request = OneTimeWorkRequestBuilder<OfficialSyncWorker>()
            .setInputData(workDataOf("tableId" to connection.timetableId, "sourceId" to connection.sourceId, "force" to force))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag("school-sync").addTag("school-sync-${connection.sourceId}").build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }
    fun next(context: Context, connection: SyncConnection) {
        if (!connection.enabled || connection.status in setOf("needs_login", "review")) return
        val midnight = SyncSafety.nextMidnight()
        work(context, connection, false, midnight.toEpochMilli() - System.currentTimeMillis(),
            "school-sync-${connection.sourceId}-${midnight.toEpochMilli()}")
    }
    fun now(context: Context, connection: SyncConnection, force: Boolean = true) {
        if (connection.enabled) work(context, connection, force, 0, "school-sync-now-${connection.sourceId}")
    }
    suspend fun maintain(context: Context, replan: Boolean = false) {
        for (connection in AppDatabase.get(context).dao().syncConnections()) {
            if (replan) cancel(context, connection.sourceId)
            next(context, connection)
            if (connection.enabled && connection.status !in setOf("needs_login", "review") && SyncSafety.due(connection.lastSuccess))
                now(context, connection, false)
        }
    }
    fun cancel(context: Context, sourceId: String) { WorkManager.getInstance(context).cancelAllWorkByTag("school-sync-$sourceId") }
}

object SyncNotifications {
    private const val CHANNEL = "official_sync"
    fun notify(context: Context, connection: SyncConnection, message: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "官方课表同步", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, connection.timetableId.toInt() + 4100,
            Intent(context, MainActivity::class.java).putExtra("openSync", true).putExtra("syncTableId", connection.timetableId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(connection.timetableId.toInt() + 4100, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("MonSched · 官方课表同步")
            .setContentText(message).setContentIntent(open).setAutoCancel(true).build())
    }
}

class OfficialSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext; val dao = AppDatabase.get(context).dao()
        val id = inputData.getLong("tableId", -1); val source = inputData.getString("sourceId") ?: return Result.success()
        var connection = dao.syncConnection(id) ?: return Result.success()
        if (!connection.enabled || connection.sourceId != source || connection.status in setOf("review", "needs_login")) return Result.success()
        if (!inputData.getBoolean("force", false) && !SyncSafety.due(connection.lastSuccess)) return Result.success()
        if (!SchoolWebSession.mutex.tryLock()) return if (runAttemptCount < 4) Result.retry() else Result.success()
        try {
            connection = dao.syncConnection(id) ?: return Result.success()
            if (!connection.enabled || connection.sourceId != source) return Result.success()
            if (!inputData.getBoolean("force", false) && !SyncSafety.due(connection.lastSuccess)) return Result.success()
            SyncScheduler.next(context, connection)
            SyncCoordinator.mutations.withLock {
                dao.syncConnection(id)?.takeIf { it.sourceId == source && it.enabled }?.let {
                    dao.saveSyncConnection(it.copy(status = "syncing", lastAttempt = System.currentTimeMillis(), message = "正在检查官方课表"))
                }
            }
            val profile = withContext(Dispatchers.IO) { runCatching { SyncVault(context).load(source) }.getOrNull() }
                ?: throw SyncNeedsLogin()
            val capture = SyncBrowser(context).fetch(profile)
            val delta = SyncCoordinator.apply(context, connection, capture.result.courses, profile = capture.profile) ?: return Result.success()
            val current = dao.syncConnection(id)
            if (current?.sourceId == source && current.enabled) {
                if (current.status == "review") SyncNotifications.notify(context, current, current.message)
                else {
                    SyncCoordinator.refresh(context)
                    if (delta.added + delta.changed + delta.removed.size > 0) SyncNotifications.notify(context, current, delta.summary)
                }
            }
            return Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val needsLogin = e is SyncNeedsLogin || e is SyncTermMissing
            val message = when (e) {
                is SyncTermMissing -> "未找到记住的学期，请重新登录并选择原学期；原课表已保留"
                is SyncNeedsLogin -> "登录失效、验证码或认证流程需要手动处理，请重新登录；原课表已保留"
                else -> "同步暂未完成，请检查网络或教务页面；原课表已保留"
            }
            SyncCoordinator.mutations.withLock {
                dao.syncConnection(id)?.takeIf { it.sourceId == source && it.enabled }?.let {
                    val failed = it.copy(status = if (needsLogin) "needs_login" else "error", message = message)
                    dao.saveSyncConnection(failed)
                    if (needsLogin || runAttemptCount >= 2) SyncNotifications.notify(context, failed, message)
                }
            }
            return if (!needsLogin && runAttemptCount < 2) Result.retry() else Result.success()
        } finally { SchoolWebSession.mutex.unlock() }
    }
}
