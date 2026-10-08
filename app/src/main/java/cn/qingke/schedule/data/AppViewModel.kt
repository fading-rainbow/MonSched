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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
        db.dao().deleteTimetable(timetable)
        switch(db.dao().timetables().first().id)
    }
    fun save(course: Course) = launchSafe { db.dao().saveCourse(course); changed(); messages.emit("课程已保存") }
    fun delete(course: Course) = launchSafe { db.dao().deleteCourse(course); changed(); messages.emit("课程已删除") }
    fun saveSlots(slots: List<TimeSlot>) = launchSafe { db.dao().saveSlots(slots); changed(); messages.emit("作息时间已保存") }
    fun setPreferences(value: Preferences) {
        preferences.value = value
        prefs.edit().putString("theme", value.theme).putBoolean("reminder", value.reminder)
            .putInt("leadMinutes", value.leadMinutes).putString("schoolUrl", value.schoolUrl)
            .putString("schoolName", value.schoolName).putString("schoolSystem", value.schoolSystem).putBoolean("allowHttp", value.allowHttp).apply()
        viewModelScope.launch { changed() }
    }
    fun importCourses(list: List<Course>, replace: Boolean, onDone: () -> Unit) = launchSafe {
        val timetable = active.value ?: return@launchSafe
        var added = 0
        db.withTransaction {
            if (replace) db.dao().clearCourses(timetable.id)
            val existing = db.dao().courses(timetable.id).map { it.key() }.toMutableSet()
            val values = list.map { it.copy(id = 0, timetableId = timetable.id) }
                .filter { existing.add(it.key()) }
            if (values.isNotEmpty()) db.dao().insertCourses(values)
            added = values.size
            val maxSections = maxOf(timetable.sectionCount, list.maxOfOrNull { it.endSection } ?: 1)
            val maxWeeks = maxOf(timetable.weekCount, list.flatMap { it.weekSet() }.maxOrNull() ?: 1)
            db.dao().updateTimetable(timetable.copy(sectionCount = maxSections, weekCount = maxWeeks))
        }
        changed(); messages.emit("已导入 $added 个上课时段"); onDone()
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
    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.emit(e.message ?: "操作失败，请重试") }
        }
    }
}
