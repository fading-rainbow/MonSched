package cn.qingke.schedule.data

import androidx.room.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

@Entity(tableName = "timetables")
data class Timetable(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "我的课表",
    val startDate: String = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString(),
    val weekCount: Int = 20,
    val sectionCount: Int = 12,
    val showWeekend: Boolean = true,
    val showOtherWeeks: Boolean = false,
    val startDateConfirmed: Boolean = false
) {
    fun currentWeek(date: LocalDate = LocalDate.now()): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(startDate), date).toInt(), 7) + 1
    fun monday(week: Int): LocalDate = LocalDate.parse(startDate).plusWeeks((week - 1).toLong())
}

@Entity(tableName = "courses", foreignKeys = [ForeignKey(entity = Timetable::class,
    parentColumns = ["id"], childColumns = ["timetableId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("timetableId")])
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long = 0,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val weekday: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val weeks: String = (1..20).joinToString(","),
    val color: Int = 0,
    val note: String = ""
) {
    fun weekSet(): Set<Int> = weeks.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    fun occurs(week: Int): Boolean = week in weekSet()
    fun key(): String = listOf(name, teacher, room, weekday, startSection, endSection, weeks).joinToString("|")
}

@Entity(tableName = "time_slots", primaryKeys = ["timetableId", "section"], foreignKeys = [ForeignKey(
    entity = Timetable::class, parentColumns = ["id"], childColumns = ["timetableId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("timetableId")])
data class TimeSlot(val timetableId: Long, val section: Int, val start: String, val end: String)

object SchoolTimes {
    // Editable example times; these are not an assertion of QIT's official timetable.
    private val starts = listOf("08:00", "08:55", "10:10", "11:05", "14:00", "14:55", "16:10", "17:05", "19:00", "19:55", "20:50", "21:45", "22:35", "23:00", "23:20", "23:40")
    fun defaults(id: Long): List<TimeSlot> = starts.mapIndexed { i, t ->
        val time = LocalTime.parse(t)
        val duration = if (i < 12) 45L else 15L
        TimeSlot(id, i + 1, t, time.plusMinutes(duration).toString())
    }
}

object WeekSpec {
    fun parse(input: String, limit: Int = 40): Set<Int> {
        val raw = input.trim().replace('，', ',').replace('、', ',').replace('；', ',')
            .replace('–', '-').replace('—', '-').replace('～', '-').replace('~', '-')
            .replace("至", "-").replace(Regex("\\s+"), "")
        require(raw.isNotEmpty()) { "请输入周次，例如 1-16 或 1-16(单)" }
        val result = linkedSetOf<Int>()
        for (part in raw.split(',').filter { it.isNotEmpty() }) {
            val odd = part.contains("单")
            val even = part.contains("双")
            require(!(odd && even)) { "单双周格式不正确" }
            val clean = part.replace(Regex("[第周单双()（）\\[\\]]"), "")
            val m = Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(clean)
                ?: throw IllegalArgumentException("周次格式不正确：$part")
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toIntOrNull() ?: start
            require(start in 1..limit && end in start..limit) { "周次应在 1-$limit 范围内，且结束不早于开始" }
            result += (start..end).filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }
        }
        require(result.isNotEmpty()) { "没有有效周次" }
        return result.toSortedSet()
    }

    fun format(weeks: Set<Int>): String {
        val sorted = weeks.sorted()
        if (sorted.isEmpty()) return ""
        val groups = mutableListOf<String>()
        var start = sorted.first(); var end = start
        for (n in sorted.drop(1)) {
            if (n == end + 1) end = n else {
                groups += if (start == end) "$start" else "$start-$end"; start = n; end = n
            }
        }
        groups += if (start == end) "$start" else "$start-$end"
        return groups.joinToString(",")
    }
}
