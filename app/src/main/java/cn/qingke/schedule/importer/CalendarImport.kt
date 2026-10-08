package cn.qingke.schedule.importer

import cn.qingke.schedule.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Imports time-based events only when they exactly match the user's section times. */
object CalendarImport {
    private data class Property(val key: String, val parameters: Map<String, String>, val value: String)
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    fun parse(text: String, table: Timetable, slots: List<TimeSlot>, zone: ZoneId = ZoneId.systemDefault()): ImportResult {
        require(table.startDateConfirmed) { "ICS 按日期和时间导入，请先确认开学日期和学校实际作息" }
        val unfolded = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
        val events = mutableListOf<List<Property>>(); var current: MutableList<Property>? = null
        for (line in unfolded.lines()) {
            if (line.equals("BEGIN:VEVENT", true)) { require(current == null) { "ICS 事件格式错误" }; current = mutableListOf(); continue }
            if (line.equals("END:VEVENT", true)) { current?.let { events += it }; current = null; continue }
            if (current != null && line.contains(':')) {
                val head = line.substringBefore(':').split(';')
                current += Property(head.first().uppercase(), head.drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=').trim('"') }, line.substringAfter(':'))
            }
        }
        require(current == null && events.size <= 3000) { "ICS 不完整或课程事件过多" }
        val warnings = mutableListOf<String>(); val output = mutableListOf<Course>()
        val overridden = events.filter { event -> event.any { it.key == "RECURRENCE-ID" } }.mapNotNull { event -> event.firstOrNull { it.key == "UID" }?.value }.toSet()
        val semesterEnd = table.monday(table.weekCount + 1)
        for ((index, event) in events.withIndex()) {
            fun value(key: String) = event.firstOrNull { it.key == key }
            val name = unescape(value("SUMMARY")?.value.orEmpty())
            try {
                if (value("STATUS")?.value?.uppercase() == "CANCELLED") continue
                require(value("UID")?.value !in overridden && value("RECURRENCE-ID") == null) { "含单次修改的重复事件，请导出展开后的日历" }
                val start = timestamp(value("DTSTART") ?: error("缺少 DTSTART"), zone)
                val finish = value("DTEND")?.let { timestamp(it, start.zone) }
                    ?: value("DURATION")?.let { start.plus(Duration.parse(it.value)) } ?: error("缺少结束时间")
                val duration = Duration.between(start, finish)
                require(!duration.isNegative && !duration.isZero && duration <= Duration.ofHours(24)) { "课程时长无效或跨日" }
                val starts = mutableSetOf(start)
                val rule = value("RRULE")?.value
                if (rule != null) starts += expand(rule, start, semesterEnd, zone)
                for (extra in event.filter { it.key == "RDATE" }) for (date in extra.value.split(',')) starts += timestamp(extra.copy(value = date), start.zone)
                val excluded = event.filter { it.key == "EXDATE" }.flatMap { property -> property.value.split(',').map { timestamp(property.copy(value = it), start.zone).toInstant() } }.toSet()
                val eventCourses = mutableListOf<Course>()
                for (occurrence in starts.sorted()) {
                    if (occurrence.toInstant() in excluded) continue
                    val local = occurrence.withZoneSameInstant(zone)
                    val end = occurrence.plus(duration).withZoneSameInstant(zone)
                    val date = local.toLocalDate(); val week = table.currentWeek(date)
                    if (week !in 1..table.weekCount) continue
                    require(date == end.toLocalDate()) { "跨午夜课程不支持节次映射" }
                    val from = slots.filter { runCatching { LocalTime.parse(it.start) == local.toLocalTime() }.getOrDefault(false) }
                    val to = slots.filter { runCatching { LocalTime.parse(it.end) == end.toLocalTime() }.getOrDefault(false) }
                    require(from.size == 1 && to.size == 1 && to.single().section >= from.single().section) {
                        "${local.toLocalTime()}–${end.toLocalTime()} 不匹配作息，请先修改作息时间"
                    }
                    val description = unescape(value("DESCRIPTION")?.value.orEmpty())
                    val teacher = value("X-TEACHER")?.value?.let(::unescape) ?: description.lineSequence().firstOrNull { it.startsWith("教师：") }?.substringAfter('：').orEmpty()
                    eventCourses += CourseParser.validate(Course(name = name, teacher = teacher, room = unescape(value("LOCATION")?.value.orEmpty()),
                        weekday = date.dayOfWeek.value, startSection = from.single().section, endSection = to.single().section,
                        weeks = week.toString(), color = Math.floorMod(name.hashCode(), 8), note = description.take(2000)))
                }
                require(output.size + eventCourses.size <= 3000) { "日历展开后的课程过多，请只导出本学期课程" }
                output += eventCourses
            } catch (e: Exception) { warnings += "ICS 第${index + 1}项（${name.ifBlank { "未命名" }}）：${e.message}" }
        }
        val grouped = output.groupBy { listOf(it.name, it.teacher, it.room, it.weekday, it.startSection, it.endSection, it.note) }.values.map { same ->
            same.first().copy(weeks = same.flatMap { it.weekSet() }.toSortedSet().joinToString(","))
        }
        return ImportResult(grouped, warnings.distinct(), "ICS 日历")
    }

    private fun timestamp(p: Property, defaultZone: ZoneId): ZonedDateTime {
        require(p.parameters["VALUE"] != "DATE" && p.value.length >= 15) { "全天事件不是课程时段" }
        val timezone = p.parameters["TZID"]?.let { ZoneId.of(if (it == "China Standard Time") "Asia/Shanghai" else it) } ?: defaultZone
        val utc = p.value.endsWith('Z')
        val date = LocalDateTime.parse(p.value.removeSuffix("Z"), timeFormat)
        return date.atZone(if (utc) ZoneOffset.UTC else timezone)
    }

    private fun expand(text: String, start: ZonedDateTime, semesterEnd: LocalDate, zone: ZoneId): List<ZonedDateTime> {
        val parts = text.uppercase().split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        require(parts.keys.all { it in setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "WKST") }) { "不支持此重复规则，请导出展开后的日历" }
        require(parts["FREQ"] in setOf("WEEKLY", "DAILY") && (parts["WKST"] ?: "MO") == "MO") { "目前支持按日／周重复，周起始为周一" }
        val interval = parts["INTERVAL"]?.toInt() ?: 1; require(interval in 1..52) { "重复间隔无效" }
        val count = parts["COUNT"]?.toInt() ?: 3000; require(count in 1..3000) { "重复次数过多或无效" }
        require(!(parts.containsKey("COUNT") && parts.containsKey("UNTIL"))) { "重复规则不能同时有 COUNT 和 UNTIL" }
        val days = parts["BYDAY"]?.split(',')?.map { name -> listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").indexOf(name).also { require(it >= 0) { "不支持带序号的重复星期" } } + 1 }?.toSet()
        if (days != null) require(start.dayOfWeek.value in days) { "起始日期与重复星期不一致" }
        val until = parts["UNTIL"]?.let {
            if (it.length == 8) LocalDate.parse(it, dateFormat).atTime(23, 59, 59).atZone(start.zone).toInstant()
            else timestamp(Property("UNTIL", emptyMap(), it), start.zone).toInstant()
        }
        require(until == null || until >= start.toInstant()) { "重复结束时间早于开始时间" }
        val baseDate = start.toLocalDate(); val baseMonday = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val horizon = semesterEnd.atStartOfDay(zone).withZoneSameInstant(start.zone).toLocalDate().plusDays(2)
        require(ChronoUnit.DAYS.between(baseDate, horizon) <= 3660) { "重复事件起始日期过早，请使用本学期日历" }
        val result = mutableListOf<ZonedDateTime>(); var date = baseDate
        while (date < horizon && result.size < count) {
            val offset = ChronoUnit.DAYS.between(baseDate, date)
            val accepted = if (parts["FREQ"] == "DAILY") offset % interval == 0L && (days == null || date.dayOfWeek.value in days)
                else ChronoUnit.WEEKS.between(baseMonday, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) % interval == 0L && date.dayOfWeek.value in (days ?: setOf(start.dayOfWeek.value))
            if (accepted) {
                val occurrence = date.atTime(start.toLocalTime()).atZone(start.zone)
                if (until != null && occurrence.toInstant() > until) break
                result += occurrence
            }
            date = date.plusDays(1)
        }
        return result
    }
    private fun unescape(text: String): String = Regex("\\\\([nN,;\\\\])").replace(text) { if (it.groupValues[1].equals("n", true)) "\n" else it.groupValues[1] }
}
