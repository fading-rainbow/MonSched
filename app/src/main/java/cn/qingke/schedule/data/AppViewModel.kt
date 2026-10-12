package cn.qingke.schedule.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import cn.qingke.schedule.importer.BackupCodec
import cn.qingke.schedule.importer.SchoolConnection
import cn.qingke.schedule.reminders.Reminders
import cn.qingke.schedule.widget.TodayWidget
import cn.qingke.schedule.widget.DayAgendaWidget
import cn.qingke.schedule.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

data class Preferences(val theme: String = "system", val reminder: Boolean = false, val leadMinutes: Int = 10,
    val schoolUrl: String = "", val schoolName: String = "", val schoolSystem: String = "zhengfang", val allowHttp: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val db = AppDatabase.get(app)
    private val prefs = app.getSharedPreferences("preferences", 0)
    val activeId = MutableStateFlow(prefs.getLong("activeId", 0))
    val preferences = MutableStateFlow(Preferences(prefs.getString("theme", "system") ?: "system",
        prefs.getBoolean("reminder", false), prefs.getInt("leadMinutes", 10),
        prefs.getString("schoolUrl", null) ?: if (prefs.contains("activeId")) SchoolConnection.QIT_URL else "",
        prefs.getString("schoolName", null) ?: if (prefs.contains("activeId") &&
            (prefs.getString("schoolUrl", SchoolConnection.QIT_URL) == SchoolConnection.QIT_URL)) "青岛工学院" else "",
        prefs.getString("schoolSystem", "zhengfang") ?: "zhengfang", prefs.getBoolean("allowHttp", false)))
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val syncConnections = db.dao().observeSyncConnections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val timetables = db.dao().observeTimetables().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val active = combine(timetables, activeId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val courses = active.filterNotNull().flatMapLatest { db.dao().observeCourses(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val slots = active.filterNotNull().flatMapLatest { db.dao().observeSlots(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { viewModelScope.launch {
        if (!prefs.contains("schoolName")) {
            val value = preferences.value
            prefs.edit().putString("schoolName", value.schoolName).putString("schoolUrl", value.schoolUrl)
                .putString("schoolSystem", value.schoolSystem).putBoolean("allowHttp", value.allowHttp).apply()
        }
        if (db.dao().timetables().isEmpty()) {
            val id = db.withTransaction {
                val newId = db.dao().insertTimetable(Timetable())
                db.dao().saveSlots(SchoolTimes.defaults(newId)); newId
            }
            switch(id)
        } else if (db.dao().timetables().none { it.id == activeId.value }) switch(db.dao().timetables().first().id)
        changed()
        SyncScheduler.maintain(app)
        withContext(Dispatchers.IO) { SyncVault(app).cleanOrphans(db.dao().syncConnections().map { it.sourceId }.toSet()) }
    } }

    fun switch(id: Long) {
        prefs.edit().putLong("activeId", id).apply(); activeId.value = id
        viewModelScope.launch { changed() }
    }
    fun create(name: String) = launchSafe {
        require(name.isNotBlank()) { "课表名称不能为空" }
        val id = db.withTransaction {
            val newId = db.dao().insertTimetable(Timetable(name = name.trim()))
            db.dao().saveSlots(SchoolTimes.defaults(newId)); newId
        }
        switch(id)
    }
    fun createDemo(onDone: () -> Unit) = launchSafe {
        val found = db.dao().timetables().firstOrNull { it.name == "示例课表" }
        val id = found?.id ?: db.withTransaction {
            val newId = db.dao().insertTimetable(Timetable(name = "示例课表", startDateConfirmed = true))
            db.dao().saveSlots(SchoolTimes.defaults(newId))
            val examples = listOf(
                Course(timetableId = newId, name = "高等数学", teacher = "示例教师", room = "示例 · A201", weekday = 1, startSection = 1, endSection = 2, color = 0),
                Course(timetableId = newId, name = "大学英语", teacher = "示例教师", room = "示例 · B302", weekday = 2, startSection = 3, endSection = 4, weeks = (1..20).filter { it % 2 == 1 }.joinToString(","), color = 1),
                Course(timetableId = newId, name = "程序设计", teacher = "示例教师", room = "示例 · 机房", weekday = 3, startSection = 1, endSection = 2, color = 3),
                Course(timetableId = newId, name = "线性代数", teacher = "示例教师", room = "示例 · A403", weekday = 3, startSection = 5, endSection = 6, color = 2),
                Course(timetableId = newId, name = "程序设计实验", teacher = "示例教师", room = "示例 · 机房", weekday = 4, startSection = 7, endSection = 8, weeks = (1..20).filter { it % 2 == 0 }.joinToString(","), color = 3),
                Course(timetableId = newId, name = "体育", teacher = "示例教师", room = "示例 · 运动场", weekday = 5, startSection = 3, endSection = 4, color = 5)
            ).map { it.copy(note = "仅用于体验 MonSched 界面，不是任何学校的真实课程。") }
            db.dao().insertCourses(examples); newId
        }
        switch(id); changed(); onDone()
    }
    fun update(timetable: Timetable) = launchSafe {
        db.dao().updateTimetable(timetable); changed()
    }
    fun deleteTimetable(timetable: Timetable) = launchSafe {
        require(db.dao().timetables().size > 1) { "至少保留一张课表" }
        SyncCoordinator.forget(getApplication(), timetable.id)
        db.dao().deleteTimetable(timetable)
        switch(db.dao().timetables().first().id)
    }
    fun save(course: Course) = launchSafe { db.dao().saveCourse(if (course.id == 0L) course.copy(syncSource = "") else course); changed(); messages.emit("课程已保存") }
    fun delete(course: Course) = launchSafe { db.dao().deleteCourse(course); changed(); messages.emit("课程已删除") }
    fun saveSlots(slots: List<TimeSlot>) = launchSafe { db.dao().saveSlots(slots); changed(); messages.emit("作息时间已保存") }
    fun setPreferences(value: Preferences) {
        preferences.value = value
        prefs.edit().putString("theme", value.theme).putBoolean("reminder", value.reminder)
            .putInt("leadMinutes", value.leadMinutes).putString("schoolUrl", value.schoolUrl)
            .putString("schoolName", value.schoolName).putString("schoolSystem", value.schoolSystem).putBoolean("allowHttp", value.allowHttp).apply()
        viewModelScope.launch { changed() }
    }
    fun importCourses(list: List<Course>, replace: Boolean, syncToken: String? = null, onFailure: () -> Unit = {}, onDone: () -> Unit) = launchSafe(onError = onFailure) {
        val timetable = active.value ?: return@launchSafe
        val profile = if (syncToken != null) withContext(Dispatchers.IO) { SyncVault(getApplication()).load(syncToken) } else null
        if (syncToken != null) require(profile != null && profile.timetableId == timetable.id) { "同步设置已失效，请重新登录读取" }
        var added = 0
        var oldConnection: SyncConnection? = null
        SyncCoordinator.mutations.lock()
        try { db.withTransaction {
            oldConnection = db.dao().syncConnection(timetable.id)
            if (replace) db.dao().clearCourses(timetable.id)
            if (profile != null) {
                val existing = db.dao().courses(timetable.id)
                val previousSource = oldConnection?.sourceId ?: profile.id
                val delta = SyncReconciler.plan(existing, list, previousSource, timetable.id, adoptExactLegacy = true)
                if (delta.removed.isNotEmpty()) db.dao().deleteCourseIds(delta.removed.map { it.id })
                for (course in delta.updates) db.dao().saveCourse(course.copy(syncSource = profile.id))
                added = delta.updates.size
                db.dao().saveSyncConnection(SyncConnection(timetable.id, profile.id, profile.schoolName.ifBlank { preferences.value.schoolName },
                    lastAttempt = System.currentTimeMillis(), lastSuccess = System.currentTimeMillis(), message = "已绑定所选学期，每日跨日后自动检查"))
            } else {
                val existing = db.dao().courses(timetable.id).map { it.identity() }.toMutableSet()
                val values = list.map { it.copy(id = 0, timetableId = timetable.id, syncSource = "") }.filter { existing.add(it.identity()) }
                if (values.isNotEmpty()) db.dao().insertCourses(values)
                added = values.size
                if (replace) oldConnection?.let { db.dao().saveSyncConnection(it.copy(enabled = false, status = "paused", message = "完整替换课表后已暂停同步")) }
            }
            val maxSections = maxOf(timetable.sectionCount, list.maxOfOrNull { it.endSection } ?: 1)
            val maxWeeks = maxOf(timetable.weekCount, list.flatMap { it.weekSet() }.maxOrNull() ?: 1)
            db.dao().updateTimetable(timetable.copy(sectionCount = maxSections, weekCount = maxWeeks))
        } } finally { SyncCoordinator.mutations.unlock() }
        if (profile != null) {
            withContext(Dispatchers.IO) { SyncVault(getApplication()).save(profile.copy(pending = false)) }
            oldConnection?.let { SyncScheduler.cancel(getApplication(), it.sourceId); if (it.sourceId != profile.id) SyncVault(getApplication()).delete(it.sourceId) }
            db.dao().syncConnection(timetable.id)?.let { SyncScheduler.next(getApplication(), it) }
        } else if (replace) oldConnection?.let { SyncScheduler.cancel(getApplication(), it.sourceId) }
        changed(); messages.emit("已导入 $added 个上课时段"); onDone()
    }
    fun setSyncEnabled(tableId: Long, enabled: Boolean) = launchSafe {
        SyncCoordinator.mutations.lock()
        try {
            val value = db.dao().syncConnection(tableId) ?: return@launchSafe
            db.dao().saveSyncConnection(value.copy(enabled = enabled,
                status = if (enabled) if (value.pendingSnapshot.isNotEmpty()) "review" else "ready" else "paused",
                message = if (enabled) "已开启每日同步" else "已暂停，保留本机登录信息"))
            if (enabled) db.dao().syncConnection(tableId)?.let { SyncScheduler.next(getApplication(), it); SyncScheduler.now(getApplication(), it) }
            else SyncScheduler.cancel(getApplication(), value.sourceId)
        } finally { SyncCoordinator.mutations.unlock() }
    }
    fun mergeDuplicates(tableId: Long, onFailure: () -> Unit = {}, onDone: () -> Unit) = launchSafe(onError = onFailure) {
        val count = SyncCoordinator.mutations.withLock {
            db.withTransaction {
                val plan = CourseDuplicates.plan(db.dao().courses(tableId))
                if (plan.removedIds.isNotEmpty()) db.dao().deleteCourseIds(plan.removedIds)
                plan.updates.forEach { db.dao().saveCourse(it) }
                plan.removedCount
            }
        }
        changed(); messages.emit("已合并 $count 条重复记录，备注和同步绑定保留"); onDone()
    }
    fun syncNow(tableId: Long) = launchSafe {
        val connection = db.dao().syncConnection(tableId) ?: return@launchSafe
        require(connection.enabled) { "请先开启每日同步" }
        require(connection.status !in setOf("needs_login", "review")) { "请先重新登录或确认待应用的课表" }
        SyncScheduler.now(getApplication(), connection); messages.emit("已安排同步，网络可用时执行")
    }
    fun forgetSync(tableId: Long) = launchSafe { SyncCoordinator.forget(getApplication(), tableId); messages.emit("已清除记住的登录信息，课程保留") }
    fun approveSync(tableId: Long) = launchSafe {
        val connection = db.dao().syncConnection(tableId) ?: return@launchSafe
        require(connection.pendingSnapshot.isNotEmpty()) { "没有待确认的课表" }
        val delta = SyncCoordinator.apply(getApplication(), connection, SyncSnapshots.decode(connection.pendingSnapshot), true)
        SyncCoordinator.refresh(getApplication())
        db.dao().syncConnection(tableId)?.let { SyncScheduler.next(getApplication(), it) }
        messages.emit(delta?.summary ?: "同步设置已变化，请重新检查")
    }
    suspend fun exportBackup(): String = db.withTransaction {
        BackupCodec.encode(db.dao().timetables().map { BackupCodec.Table(it, db.dao().courses(it.id), db.dao().slots(it.id)) })
    }
    fun restoreBackup(text: String, onDone: () -> Unit) = launchSafe {
        val tables = withContext(Dispatchers.Default) { BackupCodec.decode(text) }
        var lastId = 0L
        db.withTransaction {
            for (table in tables) {
                val suffix = " · 恢复"
                lastId = db.dao().insertTimetable(table.timetable.copy(id = 0, name = table.timetable.name.take(60 - suffix.length) + suffix))
                db.dao().saveSlots(table.slots.map { it.copy(timetableId = lastId) })
                db.dao().insertCourses(table.courses.map { it.copy(id = 0, timetableId = lastId) })
            }
        }
        if (lastId != 0L) switch(lastId)
        changed(); messages.emit("已恢复 ${tables.size} 张课表，原课表已保留"); onDone()
    }
    suspend fun changed() {
        withContext(Dispatchers.IO) {
            Reminders.schedule(getApplication())
            TodayWidget.refresh(getApplication())
            DayAgendaWidget.refresh(getApplication())
        }
    }
    private fun launchSafe(onError: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(); messages.emit(e.message ?: "操作失败，请重试") }
        }
    }
}
