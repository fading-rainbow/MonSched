package cn.qingke.schedule

import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.*
import cn.qingke.schedule.exporter.CalendarExport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UniversalImportTest {
    private val table = Timetable(id = 1, startDate = "2026-09-07", weekCount = 20, startDateConfirmed = true)
    private val slots = SchoolTimes.defaults(1)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun calendar(properties: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n$properties\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parse(properties: String) = CalendarImport.parse(calendar(properties), table, slots, zone)

    @Test fun csvSupportsEnglishHeadersDaysAndDisjointSectionColumn() {
        val parsed = CourseParser.parse("CourseName,Day,Sections,Weeks,Teacher,Room\nMath,Monday,\"1-2,5-6\",1-16(单),Teacher,A201")
        assertEquals(2, parsed.courses.size)
        assertEquals(setOf(1,3,5,7,9,11,13,15), parsed.courses.first().weekSet())
        assertEquals(5, parsed.courses.last().startSection)
    }
    @Test fun nestedQiangzhiJsonAliasesAndArraySections() {
        val parsed = CourseParser.parse("""{"data":{"kbxx":[{"kcmc":"程序设计","xq":"2","sections":[1,2],"zc":"1-16","skjs":"李老师","jxcdmc":"机房"}]}}""")
        assertEquals("机房", parsed.courses.single().room)
        assertEquals("李老师", parsed.courses.single().teacher)
        assertEquals(2, parsed.courses.single().weekday)
    }
    @Test fun qiangzhiHtmlReadsFontLabelsAndSeparatesCourses() {
        val html = """<table id="kbtable"><tr><th>节次</th><th>星期一</th></tr><tr><td>第一大节</td><td><div id="kbcontent1">
            数学<br><font title="老师">张老师</font><br><font title="周次">1-8(周)</font><br><font title="节次">1-2节</font><br><font title="教室">A201</font><hr>
            物理<br><font title="老师">王老师</font><br><font title="周次">9-16(双周)</font><br><font title="节次">1-2节</font><br><font title="教室">B301</font></div></td></tr></table>"""
        val parsed = CourseParser.parse(html)
        assertEquals(2, parsed.courses.size)
        assertEquals("张老师", parsed.courses.first().teacher)
        assertEquals("B301", parsed.courses.last().room)
        assertEquals(setOf(10,12,14,16), parsed.courses.last().weekSet())
    }
    @Test fun genericHtmlListTableUsesExplicitColumns() {
        val html = "<table><tr><th>课程名称</th><th>星期</th><th>节次</th><th>周次</th><th>教师</th></tr><tr><td>树维导出示例</td><td>周三</td><td>3-4</td><td>2-10(双)</td><td>陈老师</td></tr></table>"
        val course = CourseParser.parse(html).courses.single()
        assertEquals(setOf(2,4,6,8,10),course.weekSet())
        assertEquals(3,course.startSection)
    }
    private fun workbook(sheet: String, strings: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name,value) in mapOf("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheet, "xl/sharedStrings.xml" to strings)) {
                z.putNextEntry(ZipEntry(name)); z.write(value.toByteArray());z.closeEntry()
            }
        }
        return out.toByteArray()
    }
    @Test fun xlsxReadsSharedStringsInlineStringsAndSparseColumns() {
        val shared = "<sst>" + listOf("课程名称","星期","节次","周次","教师","高等数学","1-2,5-6","1-16(单)").joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c><c r="F1" t="s"><v>4</v></c></row>
            <row r="2"><c r="A2" t="s"><v>5</v></c><c r="B2"><v>1</v></c><c r="C2" t="s"><v>6</v></c><c r="D2" t="s"><v>7</v></c><c r="F2" t="inlineStr"><is><t>王老师</t></is></c></row></sheetData></worksheet>"""
        val result = ImportFiles.parse(workbook(sheet,shared),table,slots)
        assertEquals(2,result.courses.size)
        assertEquals("王老师",result.courses.first().teacher)
        assertEquals(5,result.courses.last().startSection)
    }
    @Test fun malformedAndFormulaWorkbooksAreRejected() {
        assertTrue(runCatching { ImportFiles.xlsx(workbook("<worksheet><sheetData><row><c r='A1'><f>1+1</f><v>2</v></c></row></sheetData></worksheet>")) }.isFailure)
        assertTrue(runCatching { ImportFiles.xlsx(byteArrayOf(1,2,3)) }.isFailure)
    }
    @Test fun icsUtcEventsMapExactlyToLocalTimes() {
        val result = parse("SUMMARY:数学\r\nDTSTART:20260907T000000Z\r\nDTEND:20260907T014000Z\r\nLOCATION:A201\r\nDESCRIPTION:教师：张老师\\n带教材")
        val course=result.courses.single()
        assertEquals("1",course.weeks);assertEquals(1,course.startSection);assertEquals(2,course.endSection)
        assertEquals("张老师",course.teacher)
    }
    @Test fun icsWeeklyRepeatHonorsCountIntervalAndExdates() {
        val result=parse("SUMMARY:英语\r\nDTSTART;TZID=Asia/Shanghai:20260907T080000\r\nDTEND;TZID=Asia/Shanghai:20260907T094000\r\nRRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=4;BYDAY=MO\r\nEXDATE;TZID=Asia/Shanghai:20260921T080000")
        assertEquals(setOf(1,5,7),result.courses.single().weekSet())
    }
    @Test fun icsDailyBydayAndUntilDoNotInventOtherDates() {
        val result=parse("SUMMARY:实验\r\nDTSTART:20260907T080000\r\nDTEND:20260907T094000\r\nRRULE:FREQ=DAILY;BYDAY=MO,WE;UNTIL=20260916T235959")
        assertEquals(2,result.courses.size)
        assertTrue(result.courses.all { it.weekSet()==setOf(1,2) })
    }
    @Test fun icsRejectsGuessingTimesUnconfirmedDatesAndUnsupportedRecurrences() {
        assertTrue(parse("SUMMARY:数学\r\nDTSTART:20260907T081000\r\nDTEND:20260907T094000").courses.isEmpty())
        assertTrue(parse("SUMMARY:数学\r\nDTSTART:20260907T080000\r\nDTEND:20260907T094000\r\nRRULE:FREQ=MONTHLY").courses.isEmpty())
        assertTrue(parse("SUMMARY:数学\r\nDTSTART:20260907T080000\r\nDTEND:20260907T094000\r\nRRULE:FREQ=WEEKLY;UNTIL=20260901T000000").courses.isEmpty())
        assertTrue(runCatching { CalendarImport.parse(calendar("SUMMARY:数学"),table.copy(startDateConfirmed=false),slots,zone) }.isFailure)
    }
    @Test fun exportedCalendarCanBeImportedWithoutLosingWeeks() {
        val course=Course(id=1,timetableId=1,name="数学,实验",room="A201",teacher="张老师",weeks="1,3,5")
        val result=CalendarImport.parse(CalendarExport.encode(table,listOf(course),slots,zone),table,slots,zone)
        assertEquals(setOf(1,3,5),result.courses.flatMap { it.weekSet() }.toSet())
        assertTrue(result.courses.all { it.name==course.name && it.startSection==1 && it.endSection==2 && it.teacher==course.teacher })
    }
    @Test fun schoolUrlsSupportDifferentZhengfangContextsAndExplicitHttp() {
        assertEquals("https://jw.example.edu/academic/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151",SchoolConnection.zhengfangEntry("https://jw.example.edu/academic/xtgl/login_slogin.html"))
        assertTrue(SchoolConnection.valid("https://sso.other.example/cas/login"))
        assertFalse(SchoolConnection.valid("http://jw.example.edu/login"))
        assertTrue(SchoolConnection.valid("http://jw.example.edu/login",allowHttp=true))
        assertFalse(SchoolConnection.valid("http://other.example/login","http://jw.example.edu/login",true))
        assertFalse(SchoolConnection.valid("https://user:password@jw.example.edu/login"))
        assertNull(SchoolConnection.zhengfangEntry("https://sso.example/cas/login"))
    }
}
