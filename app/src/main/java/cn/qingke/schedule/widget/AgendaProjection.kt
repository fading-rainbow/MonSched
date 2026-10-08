package cn.qingke.schedule.widget

import cn.qingke.schedule.data.Course
import cn.qingke.schedule.data.TimeSlot
import cn.qingke.schedule.data.Timetable
import java.time.*
import java.time.format.DateTimeFormatter

data class AgendaRow(val course: Course, val time: String, val ongoing: Boolean,
    val start: Instant?, val end: Instant?)

data class AgendaDay(val date: LocalDate, val subtitle: String, val rows: List<AgendaRow>,
    val total: Int, val emptyMessage: String)

/** A view of the schedule; completed meetings are never removed from storage. */
object AgendaProjection {
    fun day(table: Timetable?, courses: List<Course>, slots: List<TimeSlot>, offset: Int,
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): AgendaDay {
        val today = now.atZone(zone).toLocalDate()
        val date = today.plusDays(offset.coerceIn(0, 1).toLong())
        val week = table?.currentWeek(date) ?: 0
        val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
        val prefix = date.format(DateTimeFormatter.ofPattern("M/d")) + " 周$weekday"
        val problem = when {
            table == null -> "点击添加课表"
            !table.startDateConfirmed -> "请先确认开学日期"
            week < 1 -> "学期尚未开始"
            week > table.weekCount -> "学期已结束"
            else -> null
        }
        if (problem != null) return AgendaDay(date, prefix, emptyList(), 0, problem)
        val bySection = slots.associateBy { it.section }
        val all = courses.filter { it.timetableId == table?.id && it.weekday == date.dayOfWeek.value && it.occurs(week) }
            .sortedWith(compareBy<Course> { it.startSection }.thenBy { it.endSection }.thenBy { it.id })
            .map { course ->
                val start = bySection[course.startSection]?.start?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val end = bySection[course.endSection]?.end?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val valid = start != null && end != null && end > start
                val startAt = if (valid) date.atTime(start).atZone(zone).toInstant() else null
                val endAt = if (valid) date.atTime(end).atZone(zone).toInstant() else null
                val label = if (valid) "$start–$end" else "${course.startSection}–${course.endSection}节 · 待设作息"
                AgendaRow(course, label, date == today && startAt != null && endAt != null && now >= startAt && now < endAt, startAt, endAt)
            }
        val rows = all.filter { date != today || it.end == null || now < it.end }
        val empty = if (all.isNotEmpty() && rows.isEmpty()) "今日课程已结束" else if (offset == 1) "明天没有课程" else "今天没有课程"
        return AgendaDay(date, "$prefix · 第${week}周", rows, all.size, empty)
    }

    fun nextRefresh(table: Timetable?, courses: List<Course>, slots: List<TimeSlot>,
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant {
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val boundaries = day(table, courses, slots, 0, now, zone).rows.flatMap { listOfNotNull(it.start, it.end) }
        return (boundaries.filter { it > now } + midnight).minOrNull()!!
    }
}
