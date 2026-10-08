package cn.qingke.schedule

import cn.qingke.schedule.data.*
import cn.qingke.schedule.widget.AgendaProjection
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AgendaTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val table = Timetable(id = 1, startDate = "2026-09-07", startDateConfirmed = true)
    private val slots = SchoolTimes.defaults(1)
    private val courses = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8).mapIndexed { i, (start, end) ->
        Course(id = i + 1L, timetableId = 1, name = "课程${i + 1}", startSection = start, endSection = end, weeks = "1")
    }
    private fun time(dateTime: String) = LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    @Test fun fourMeetingsBecomeFourCardsAndDisappearAtTheLastSectionEnd() {
        val early = AgendaProjection.day(table, courses.reversed(), slots, 0, time("2026-09-07T07:00:00"), zone)
        assertEquals(listOf(1L, 2L, 3L, 4L), early.rows.map { it.course.id })
        val duringBreak = AgendaProjection.day(table, courses, slots, 0, time("2026-09-07T08:50:00"), zone)
        assertEquals(4, duringBreak.rows.size) // Contiguous 1–2 sections remain one meeting.
        assertTrue(duringBreak.rows.first().ongoing)
        val beforeEnd = AgendaProjection.day(table, courses, slots, 0, time("2026-09-07T09:39:59"), zone)
        assertEquals(4, beforeEnd.rows.size)
        val afterEnd = AgendaProjection.day(table, courses, slots, 0, time("2026-09-07T09:40:00"), zone)
        assertEquals(listOf(2L, 3L, 4L), afterEnd.rows.map { it.course.id })
        assertEquals(4, afterEnd.total)
        assertEquals(4, courses.size) // Filtering never mutates the source schedule.
        val finished = AgendaProjection.day(table, courses, slots, 0, time("2026-09-07T18:00:00"), zone)
        assertTrue(finished.rows.isEmpty())
        assertEquals("今日课程已结束", finished.emptyMessage)
    }
    @Test fun tomorrowKeepsAllMeetingsAndUsesItsOwnWeekAtSundayBoundary() {
        val nextWeek = courses.first().copy(weeks = "2")
        val sunday = time("2026-09-13T23:00:00")
        val tomorrow = AgendaProjection.day(table, courses + nextWeek, slots, 1, sunday, zone)
        assertEquals(LocalDate.parse("2026-09-14"), tomorrow.date)
        assertEquals(listOf(nextWeek), tomorrow.rows.map { it.course })
        assertFalse(tomorrow.rows.single().ongoing)
        assertTrue(tomorrow.subtitle.contains("第2周"))
    }
    @Test fun afterMidnightTodayAndTomorrowRollForward() {
        val tuesday = courses.first().copy(id = 5, weekday = 2)
        val before = AgendaProjection.day(table, courses + tuesday, slots, 1, time("2026-09-07T23:59:59"), zone)
        val after = AgendaProjection.day(table, courses + tuesday, slots, 0, time("2026-09-08T00:00:00"), zone)
        assertEquals(before.rows.map { it.course }, after.rows.map { it.course })
        assertEquals(listOf(tuesday), after.rows.map { it.course })
    }
    @Test fun unconfirmedAndOutOfTermNeverShowMisleadingCourses() {
        assertTrue(AgendaProjection.day(table.copy(startDateConfirmed = false), courses, slots, 0,
            time("2026-09-07T07:00:00"), zone).rows.isEmpty())
        assertEquals("学期已结束", AgendaProjection.day(table.copy(weekCount = 1), courses, slots, 1,
            time("2026-09-13T07:00:00"), zone).emptyMessage)
        assertEquals("学期尚未开始", AgendaProjection.day(table, courses, slots, 0,
            time("2026-09-06T07:00:00"), zone).emptyMessage)
    }
    @Test fun missingOrInvalidTimesKeepCardsWithoutGuessingAnEnd() {
        val one = listOf(courses.first())
        for (times in listOf(emptyList(), slots.filter { it.section != 2 }, slots.map { if (it.section == 2) it.copy(end = "bad") else it },
            slots.map { if (it.section == 2) it.copy(end = "07:00") else it })) {
            val result = AgendaProjection.day(table, one, times, 0, time("2026-09-07T20:00:00"), zone)
            assertEquals(one, result.rows.map { it.course })
            assertNull(result.rows.single().end)
            assertTrue(result.rows.single().time.contains("待设作息"))
        }
    }
    @Test fun refreshPlannerChoosesStartThenEndThenNextCourseAndMidnight() {
        assertEquals(time("2026-09-07T08:00:00"), AgendaProjection.nextRefresh(table, courses, slots, time("2026-09-07T07:00:00"), zone))
        assertEquals(time("2026-09-07T09:40:00"), AgendaProjection.nextRefresh(table, courses, slots, time("2026-09-07T08:00:00"), zone))
        assertEquals(time("2026-09-07T10:10:00"), AgendaProjection.nextRefresh(table, courses, slots, time("2026-09-07T09:40:00"), zone))
        assertEquals(time("2026-09-08T00:00:00"), AgendaProjection.nextRefresh(table, courses, slots, time("2026-09-07T18:00:00"), zone))
        assertEquals(time("2026-09-08T00:00:00"), AgendaProjection.nextRefresh(null, emptyList(), emptyList(), time("2026-09-07T18:00:00"), zone))
    }
    @Test fun activeTableAndOddEvenWeeksAreRespected() {
        val wrong = courses.first().copy(timetableId = 2)
        val week2 = courses.first().copy(weeks = "2,4")
        val result = AgendaProjection.day(table, listOf(wrong, week2), slots, 0, time("2026-09-07T07:00:00"), zone)
        assertTrue(result.rows.isEmpty())
        assertEquals("今天没有课程", result.emptyMessage)
    }
}
