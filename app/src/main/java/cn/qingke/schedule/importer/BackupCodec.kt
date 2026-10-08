package cn.qingke.schedule.importer

import cn.qingke.schedule.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

object BackupCodec {
    data class Table(val timetable: Timetable, val courses: List<Course>, val slots: List<TimeSlot>)
    fun encode(tables: List<Table>): String = JSONObject().put("kind", "qingke-backup").put("schemaVersion", 1)
        .put("createdAt", java.time.Instant.now().toString()).put("tables", JSONArray().apply {
            tables.forEach { t -> put(JSONObject().put("name", t.timetable.name).put("startDate", t.timetable.startDate)
                .put("weekCount", t.timetable.weekCount).put("sectionCount", t.timetable.sectionCount)
                .put("showWeekend", t.timetable.showWeekend).put("showOtherWeeks", t.timetable.showOtherWeeks)
                .put("startDateConfirmed", t.timetable.startDateConfirmed)
                .put("courses", JSONArray().apply { t.courses.forEach { c -> put(JSONObject()
                    .put("name", c.name).put("teacher", c.teacher).put("room", c.room).put("weekday", c.weekday)
                    .put("startSection", c.startSection).put("endSection", c.endSection).put("weeks", c.weeks)
                    .put("color", c.color).put("note", c.note)) } })
                .put("slots", JSONArray().apply { t.slots.forEach { put(JSONObject().put("section", it.section).put("start", it.start).put("end", it.end)) } })) }
        }).toString(2)

    fun decode(text: String): List<Table> {
        require(text.length <= 5 * 1024 * 1024) { "备份文件过大" }
        val root = JSONObject(text)
        require(root.optString("kind") == "qingke-backup" && root.optInt("schemaVersion") == 1) { "这不是受支持的课表备份文件" }
        val tables = root.getJSONArray("tables")
        require(tables.length() in 1..50) { "备份课表数量无效" }
        return (0 until tables.length()).map { i ->
            val obj = tables.getJSONObject(i)
            val date = LocalDate.parse(obj.getString("startDate"))
            require(date.dayOfWeek == java.time.DayOfWeek.MONDAY) { "学期开始日期必须为周一" }
            val t = Timetable(name = obj.getString("name"), startDate = date.toString(),
                weekCount = obj.getInt("weekCount"), sectionCount = obj.getInt("sectionCount"),
                showWeekend = obj.optBoolean("showWeekend", true), showOtherWeeks = obj.optBoolean("showOtherWeeks", false),
                startDateConfirmed = obj.optBoolean("startDateConfirmed", true))
            require(t.name.isNotBlank() && t.name.length <= 60 && t.weekCount in 1..40 && t.sectionCount in 1..16) { "备份课表设置无效" }
            val parsed = CourseParser.parseJson(JSONObject().put("courses", obj.getJSONArray("courses")).toString())
            require(parsed.warnings.isEmpty()) { "备份课程存在无效数据：${parsed.warnings.firstOrNull()}" }
            require(parsed.courses.all { it.endSection <= t.sectionCount && it.weekSet().all { w -> w <= t.weekCount } }) { "备份课程超出课表范围" }
            val array = obj.getJSONArray("slots")
            require(array.length() <= 16) { "作息时间数量无效" }
            val slots = (0 until array.length()).map { n ->
                val slot = array.getJSONObject(n)
                val start = LocalTime.parse(slot.getString("start")); val end = LocalTime.parse(slot.getString("end"))
                require(end > start) { "作息结束时间必须晚于开始时间" }
                TimeSlot(0, slot.getInt("section"), start.toString(), end.toString()).also { require(it.section in 1..16) }
            }
            require(slots.map { it.section }.toSet().size == slots.size) { "作息节次重复" }
            require((1..t.sectionCount).all { s -> slots.any { it.section == s } }) { "作息时间不完整" }
            Table(t, parsed.courses, slots)
        }
    }
    fun csv(courses: List<Course>): String {
        fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
        return "\uFEFF课程名称,教师,教室,星期,开始节,结束节,周次,备注\n" + courses.joinToString("\n") { c ->
            listOf(c.name, c.teacher, c.room, c.weekday.toString(), c.startSection.toString(), c.endSection.toString(),
                WeekSpec.format(c.weekSet()), c.note).joinToString(",", transform = ::quote)
        }
    }
}
