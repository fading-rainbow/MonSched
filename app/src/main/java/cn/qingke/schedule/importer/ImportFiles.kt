package cn.qingke.schedule.importer

import cn.qingke.schedule.data.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object ImportFiles {
    fun parse(bytes: ByteArray, table: Timetable, slots: List<TimeSlot>): ImportResult {
        require(bytes.size <= 5 * 1024 * 1024) { "请选择 5 MB 以内的文件" }
        val result = if (bytes.take(2) == listOf(0x50.toByte(), 0x4B.toByte())) xlsx(bytes) else {
            require(bytes.firstOrNull() != 0xD0.toByte()) { "旧版二进制 XLS 请用 Excel 另存为 XLSX 或 CSV 后导入" }
            val utf8 = bytes.toString(Charsets.UTF_8)
            val text = (if (utf8.contains('\uFFFD')) bytes.toString(charset("GB18030")) else utf8).trim().removePrefix("\uFEFF")
            if (text.startsWith("BEGIN:VCALENDAR", true)) CalendarImport.parse(text, table, slots) else CourseParser.parse(text)
        }
        require(result.courses.isNotEmpty()) { "没有可导入的课程。" + result.warnings.take(3).joinToString("\n") }
        return result
    }

    fun xlsx(bytes: ByteArray): ImportResult {
        val entries = mutableMapOf<String, String>()
        var total = 0; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 200) { "Excel 文件条目过多" }
                val relevant = entry.name == "xl/sharedStrings.xml" || entry.name == "xl/workbook.xml" || entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    total += n; require(total <= 20 * 1024 * 1024) { "Excel 解压内容过大，请只保留课表数据" }
                    if (relevant) out.write(buffer, 0, n)
                }
                if (relevant) entries[entry.name] = out.toString("UTF-8")
                zip.closeEntry()
            }
        }
        require(entries.containsKey("xl/workbook.xml")) { "这不是有效的 XLSX 文件" }
        fun xml(value: String) = Jsoup.parse(value, "", Parser.xmlParser())
        val shared = entries["xl/sharedStrings.xml"]?.let { xml(it).select("si").map { si -> si.select("t").joinToString("") { it.wholeText() } } }.orEmpty()
        val courses = mutableListOf<Course>(); val warnings = mutableListOf<String>()
        for ((name, data) in entries.filterKeys { it.startsWith("xl/worksheets/") }.toSortedMap()) {
            val rows = xml(data).select("sheetData > row")
            require(rows.size <= 3001) { "Excel 单表最多 3000 门课程" }
            val values = rows.map { row ->
                val cells = sortedMapOf<Int, String>()
                for (c in row.select("c")) {
                    val letters = c.attr("r").takeWhile { it.isLetter() }.uppercase()
                    val column = letters.fold(0) { acc, ch -> acc * 26 + ch.code - 'A'.code + 1 } - 1
                    require(column in 0..100) { "Excel 列过多" }
                    require(c.select("f").isEmpty()) { "Excel 含公式，请先粘贴为值再导入" }
                    val value = when (c.attr("t")) {
                        "s" -> { val index = c.selectFirst("v")?.text()?.toIntOrNull(); require(index != null && index in shared.indices) { "Excel 字符串索引无效" }; shared[index] }
                        "inlineStr" -> c.select("is t").joinToString("") { it.wholeText() }
                        else -> c.selectFirst("v")?.text().orEmpty()
                    }
                    cells[column] = value
                }
                if (cells.isEmpty()) emptyList() else (0..cells.lastKey()).map { cells[it].orEmpty() }
            }.filter { row -> row.any { it.isNotBlank() } }
            val header = values.indexOfFirst { row -> row.any { value -> listOf("课程名称", "课程", "name", "courseName", "kcmc").any { value.trim().equals(it, true) } } }
            if (header < 0) { warnings += "$name 未找到模板表头，已跳过"; continue }
            runCatching { CourseParser.parseRows(values.drop(header), "XLSX") }.fold(
                onSuccess = { courses += it.courses; warnings += it.warnings },
                onFailure = { warnings += "$name：${it.message}" })
        }
        require(courses.size <= 3000) { "Excel 课程总数过多" }
        return ImportResult(courses.distinctBy { it.key() }, warnings, "Excel XLSX")
    }
}
