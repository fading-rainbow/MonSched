package cn.qingke.schedule

import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.*
import cn.qingke.schedule.exporter.CalendarExport
import cn.qingke.schedule.reminders.ReminderPlanner
import cn.qingke.schedule.ui.placeCourses
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreTest {
    private val table = Timetable(id = 1, name = "测试学期", startDate = "2026-09-07", startDateConfirmed = true)
    private val course = Course(id = 1, timetableId = 1, name = "高等数学", room = "A201", teacher = "张老师", weeks = "1,3,5", note = "带教材")

    @Test fun weeksHandleOddEvenAndChineseSeparators() {
        assertEquals(setOf(1, 3, 5, 8, 10), WeekSpec.parse("1—6周(单周)，8-10周（双）"))
        assertEquals(setOf(1, 2, 4, 7), WeekSpec.parse("第1-2周、4周、7周"))
        assertEquals("1-3,5,7-8", WeekSpec.format(setOf(1, 2, 3, 5, 7, 8)))
    }
    @Test fun malformedWeeksAreRejected() {
        listOf("", "0-4", "4-1", "1-41", "1-3(单双)", "一到十", "1/2").forEach {
            assertTrue("must reject $it", runCatching { WeekSpec.parse(it) }.isFailure)
        }
    }
    @Test fun currentWeekUsesFloorBeforeSemesterAndMondayBoundaries() {
        assertEquals(0, table.currentWeek(LocalDate.parse("2026-09-06")))
        assertEquals(-1, table.currentWeek(LocalDate.parse("2026-08-30")))
        assertEquals(1, table.currentWeek(LocalDate.parse("2026-09-07")))
        assertEquals(1, table.currentWeek(LocalDate.parse("2026-09-13")))
        assertEquals(2, table.currentWeek(LocalDate.parse("2026-09-14")))
    }
    @Test fun disjointSectionsStaySeparate() {
        assertEquals(listOf(1..2, 5..6), CourseParser.sections("第1,2,5-6节"))
        assertTrue(runCatching { CourseParser.sections("2-1") }.isFailure)
        assertTrue(runCatching { CourseParser.sections("17") }.isFailure)
    }
    @Test fun zhengfangJsonReadsWeekParityAndMultipleMeetings() {
        val data = """{"kbList":[{"kcmc":"程序设计","xm":"刘老师","cdmc":"机房","xqj":"3","jcs":"1-2,5-6","zcd":"1-16周(单)"}]}"""
        val result = CourseParser.parse(data)
        assertEquals(2, result.courses.size); assertTrue(result.warnings.isEmpty())
        assertEquals(3, result.courses.first().weekday)
        assertEquals((1..16).filter { it % 2 == 1 }.toSet(), result.courses.first().weekSet())
        assertEquals("刘老师", result.courses.first().teacher)
        assertEquals(5, result.courses.last().startSection)
    }
    @Test fun jcorEncodingIsDecodedAndInvalidRowsReported() {
        val result = CourseParser.parse("""{"kbList":[{"kcmc":"数学","xqj":"1","jcor":"0102","zcd":"1-10周"},{"kcmc":"坏课程","xqj":"8","jcs":"1-2","zcd":"1-10周"}]}""")
        assertEquals(1, result.courses.size); assertEquals(1, result.warnings.size)
        assertEquals(2, result.courses.first().endSection)
    }
    @Test fun missingWeeksDoNotSilentlyInventSemester() {
        val result = CourseParser.parseJson("""{"kbList":[{"kcmc":"数学","xqj":"1","jcs":"1-2"}]}""")
        assertTrue(result.courses.isEmpty()); assertEquals(1, result.warnings.size)
    }
    @Test fun duplicateJsonCoursesAreCollapsed() {
        val row = """{"name":"英语","weekday":1,"startSection":1,"endSection":2,"weeks":[1,2,3]}"""
        assertEquals(1, CourseParser.parse("[$row,$row]").courses.size)
    }
    @Test fun quotedCsvAndMultilineNotesRoundTrip() {
        val special = course.copy(name = "设计,实验", teacher = "王\"老师", note = "第一行\n第二行")
        val parsed = CourseParser.parse(BackupCodec.csv(listOf(special))).courses.single()
        assertEquals(special.copy(id = 0, timetableId = 0, color = Math.floorMod(special.name.hashCode(), 8)), parsed)
    }
    @Test fun malformedCsvReportsIndividualRows() {
        val csv = "课程名称,教师,教室,星期,开始节,结束节,周次\n数学,王,A1,1,1,2,1-16\n英语,刘,B1,3,4,2,1-16"
        val result = CourseParser.parse(csv)
        assertEquals(1, result.courses.size); assertEquals(1, result.warnings.size)
        assertTrue(runCatching { CourseParser.parse("课程名称,周次\n数学,1-16") }.isFailure)
    }
    @Test fun oldHtmlTableInfersDaysWithRowspan() {
        val html = """<table id="Table1"><tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
            <tr><td>第1节</td><td rowspan="2">高等数学<br>周一第1,2节{第1-16周}<br>张老师<br>A201</td><td></td><td></td></tr>
            <tr><td>第2节</td><td>大学英语<br>周二第2节{第2-10周(双周)}<br>李老师<br>B302</td><td></td></tr></table>"""
        val result = CourseParser.parse(html)
        assertEquals(2, result.courses.size)
        val math = result.courses.first { it.name == "高等数学" }
        assertEquals("张老师", math.teacher); assertEquals("A201", math.room)
        val english = result.courses.first { it.name == "大学英语" }
        assertEquals(2, english.weekday); assertEquals(setOf(2, 4, 6, 8, 10), english.weekSet())
    }
    @Test fun modernHtmlUsesTitlesForTeacherAndLocation() {
        val html = """<table id="kbgrid_table_0"><tr><th>节次</th><th>周一</th></tr><tr><td>第1节</td><td>
            <div class="timetable_con"><span class="title"><b>线性代数</b></span><p>(1-2节)1-18周</p>
            <p><span title="教师">赵老师</span></p><p><span title="上课地点">教学楼201</span></p></div></td></tr></table>"""
        val c = CourseParser.parse(html).courses.single()
        assertEquals("线性代数", c.name); assertEquals("赵老师", c.teacher); assertEquals("教学楼201", c.room)
    }
    @Test fun multipleCoursesInAnOldHtmlCellRemainSeparate() {
        val html = """<table><tr><th>节次</th><th>星期一</th></tr><tr><td>第1节</td><td>
            数学<br>周一第1,2节{第1-8周}<br>张老师<br>A201<br><br>
            物理<br>周一第1,2节{第9-16周}<br>李老师<br>B201</td></tr></table>"""
        val result = CourseParser.parse(html)
        assertEquals(listOf("数学", "物理"), result.courses.map { it.name })
        assertEquals((1..8).toSet(), result.courses[0].weekSet())
        assertEquals((9..16).toSet(), result.courses[1].weekSet())
    }
    @Test fun loginPagesCannotBeMistakenForCourses() {
        assertTrue(runCatching { CourseParser.parse("<html><form><input value='secret'></form><table><tr><td>登录</td></tr></table></html>") }.isFailure)
    }
    @Test fun fullBackupAndEmptyTimetableRoundTrip() {
        val data = BackupCodec.encode(listOf(BackupCodec.Table(table, listOf(course), SchoolTimes.defaults(1)),
            BackupCodec.Table(table.copy(id = 2, name = "空课表"), emptyList(), SchoolTimes.defaults(2))))
        val restored = BackupCodec.decode(data)
        assertEquals(2, restored.size); assertEquals(table.copy(id = 0), restored[0].timetable)
        assertEquals(course.copy(id = 0, timetableId = 0), restored[0].courses.single())
        assertTrue(restored[1].courses.isEmpty())
    }
    @Test fun brokenBackupIsRejectedBeforeAnyWrites() {
        val data = BackupCodec.encode(listOf(BackupCodec.Table(table, listOf(course), SchoolTimes.defaults(1).take(2))))
        assertTrue(runCatching { BackupCodec.decode(data) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode("{\"kind\":\"qingke-backup\",\"schemaVersion\":999,\"tables\":[]}") }.isFailure)
    }
    @Test fun calendarUsesActualWeeksAndUtcAndEscapesText() {
        val c = course.copy(name = "数学,实验;A", note = "注意\n第二行")
        val output = CalendarExport.encode(table, listOf(c), SchoolTimes.defaults(1), ZoneId.of("Asia/Shanghai"))
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(output).count())
        assertTrue(output.contains("DTSTART:20260907T000000Z"))
        assertTrue(output.contains("DTSTART:20260921T000000Z"))
        assertFalse(output.contains("DTSTART:20260914T000000Z"))
        assertTrue(output.contains("SUMMARY:数学\\,实验\\;A"))
        assertTrue(output.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
    }
    @Test fun nextReminderSkipsInactiveWeeksAndAlreadyElapsedReminderTimes() {
        val slots = SchoolTimes.defaults(1); val zone = ZoneId.of("Asia/Shanghai")
        val next = ReminderPlanner.next(table, listOf(course), slots, 10, Instant.parse("2026-09-06T16:00:00Z"), zone)!!
        assertEquals(Instant.parse("2026-09-06T23:50:00Z").toEpochMilli(), next.notifyAt)
        val later = ReminderPlanner.next(table, listOf(course), slots, 10, Instant.parse("2026-09-06T23:51:00Z"), zone)!!
        assertEquals(Instant.parse("2026-09-20T23:50:00Z").toEpochMilli(), later.notifyAt)
        assertNull(ReminderPlanner.next(table, listOf(course), slots, 10, Instant.parse("2027-01-01T00:00:00Z"), zone))
    }
    @Test fun gridAllocatesOverlapsToLanesAndReusesFreedLane() {
        val placements = placeCourses(listOf(course, course.copy(id = 2, startSection = 2, endSection = 3),
            course.copy(id = 3, startSection = 3, endSection = 4), course.copy(id = 4, startSection = 6, endSection = 7)))
        assertEquals(2, placements[0].lanes)
        assertEquals(0, placements.first { it.course.id == 3L }.lane)
        assertEquals(1, placements.first { it.course.id == 4L }.lanes)
    }
}
