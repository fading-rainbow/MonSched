package cn.qingke.schedule.importer

import cn.qingke.schedule.data.Course
import cn.qingke.schedule.data.WeekSpec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class ImportResult(val courses: List<Course>, val warnings: List<String> = emptyList(), val source: String = "文件")

object CourseParser {
    fun parse(text: String): ImportResult {
        require(text.length <= 5 * 1024 * 1024) { "文件过大，请选择 5 MB 以内的课表文件" }
        val trimmed = text.trim().removePrefix("\uFEFF")
        require(trimmed.isNotEmpty()) { "文件为空" }
        return when {
            trimmed.startsWith('{') || trimmed.startsWith('[') -> parseJson(trimmed)
            trimmed.startsWith('<') -> parseHtml(trimmed)
            else -> parseCsv(trimmed)
        }.also { require(it.courses.isNotEmpty()) {
            if (it.warnings.isNotEmpty()) "未识别到有效课程：\n" + it.warnings.take(3).joinToString("\n")
            else "未识别到课程。请先在教务系统打开学生课表页，选择学年学期并查询后再导入。"
        } }
    }

    fun parseJson(text: String): ImportResult {
        val root = if (text.trim().startsWith('[')) null else JSONObject(text)
        if (root?.has("json") == true || root?.has("html") == true) {
            val json = root.opt("json")
            if (json is JSONObject || json is JSONArray) {
                runCatching { parseJson(json.toString()) }.getOrNull()?.let { if (it.courses.isNotEmpty()) return it }
            }
            return parseHtml(root.optString("html"))
        }
        require(root?.optString("kind") != "qingke-backup") { "这是课表备份文件，请使用“恢复备份”" }
        val rows = root?.optJSONArray("kbList") ?: root?.optJSONArray("courses")
            ?: root?.optJSONObject("data")?.optJSONArray("kbList")
            ?: root?.optJSONArray("data") ?: root?.optJSONArray("kbxx") ?: root?.optJSONArray("rows")
            ?: root?.optJSONObject("data")?.optJSONArray("courses") ?: root?.optJSONObject("data")?.optJSONArray("kbxx")
            ?: if (root == null) JSONArray(text) else throw IllegalArgumentException("JSON 中没有受支持的课程数组：kbList、courses、kbxx、rows 或 data")
        require(rows.length() <= 3000) { "课程数量过多" }
        val courses = mutableListOf<Course>(); val warnings = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)
            if (row == null) { warnings += "第 ${i + 1} 条数据不是课程对象"; continue }
            try {
                val name = first(row, "kcmc", "name", "courseName").trim()
                require(name.isNotEmpty()) { "课程名称为空" }
                val day = day(first(row, "xqj", "weekday", "day", "xqjmc", "xq"))
                val sectionValue = row.opt("sections")
                val sectionText = (if (sectionValue is JSONArray) (0 until sectionValue.length()).joinToString(",") { sectionValue.get(it).toString() }
                    else first(row, "jcs", "jc", "sections")).ifEmpty {
                    if (row.has("startSection")) "${row.getInt("startSection")}-${row.optInt("endSection", row.getInt("startSection"))}"
                    else decodeJcor(first(row, "jcor", "jcdm"))
                }
                val ranges = sections(sectionText)
                val weekValue = row.opt("weeks")
                val weekText = if (weekValue is JSONArray) (0 until weekValue.length()).joinToString(",") { weekValue.get(it).toString() }
                    else first(row, "zcd", "weeks", "weekText", "zcs", "zc")
                val weeks = WeekSpec.parse(weekText).joinToString(",")
                for (r in ranges) courses += validate(Course(name = name,
                    teacher = first(row, "xm", "jsxm", "teacher", "jsmc", "skjs"),
                    room = first(row, "cdmc", "room", "classroom", "jxcdmc", "skdd"),
                    weekday = day, startSection = r.first, endSection = r.last, weeks = weeks,
                    color = row.optInt("color", Math.floorMod(name.hashCode(), 8)), note = row.optString("note")))
            } catch (e: Exception) { warnings += "第 ${i + 1} 条（${row.optString("kcmc", row.optString("name", "未命名"))}）：${e.message}" }
        }
        return ImportResult(courses.distinctBy { it.key() }, warnings, "教务 / 通用 JSON")
    }

    fun parseCsv(text: String): ImportResult = parseRows(csvRows(text), "CSV")

    fun parseRows(rows: List<List<String>>, source: String): ImportResult {
        require(rows.size >= 2) { "$source 至少需要表头和一行课程" }
        val headers = rows.first().map { it.trim().removePrefix("\uFEFF") }
        fun col(vararg names: String): Int = headers.indexOfFirst { header -> names.any { header.equals(it, true) } }
        val nameCol = col("课程名称", "课程", "name", "kcmc", "courseName")
        val dayCol = col("星期", "星期几", "weekday", "xqj", "day")
        val startCol = col("开始节", "开始节次", "startSection", "start_section")
        val endCol = col("结束节", "结束节次", "endSection", "end_section")
        val sectionCol = col("节次", "上课节次", "sections", "jcs")
        val weekCol = col("周次", "weeks", "zcd")
        require(listOf(nameCol, dayCol, weekCol).all { it >= 0 } && (sectionCol >= 0 || startCol >= 0 && endCol >= 0)) {
            "$source 表头应包含：课程名称,教师,教室,星期,开始节,结束节,周次"
        }
        val teacherCol = col("教师", "老师", "teacher", "xm")
        val roomCol = col("教室", "地点", "room", "cdmc")
        val noteCol = col("备注", "note")
        val courses = mutableListOf<Course>(); val warnings = mutableListOf<String>()
        for ((index, row) in rows.drop(1).withIndex()) {
            if (row.all { it.isBlank() }) continue
            fun at(i: Int) = row.getOrNull(i)?.trim().orEmpty()
            try {
                val name = at(nameCol)
                val ranges = if (sectionCol >= 0) sections(at(sectionCol)) else listOf(at(startCol).toInt()..at(endCol).toInt())
                for (range in ranges) courses += validate(Course(name = name, teacher = at(teacherCol), room = at(roomCol),
                    weekday = day(at(dayCol)), startSection = range.first, endSection = range.last,
                    weeks = WeekSpec.parse(at(weekCol)).joinToString(","), color = Math.floorMod(name.hashCode(), 8), note = at(noteCol)))
            } catch (e: Exception) { warnings += "$source 第 ${index + 2} 行：${e.message}" }
        }
        return ImportResult(courses.distinctBy { it.key() }, warnings, source)
    }

    fun parseHtml(html: String): ImportResult {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<Course>(); val warnings = mutableListOf<String>()
        for (table in doc.select("table")) {
            val tabular = table.select("tr").filter { it.closest("table") == table }.map { row -> row.children().filter { it.tagName() in listOf("td", "th") }.map { it.text() } }
            if (tabular.firstOrNull()?.any { value -> listOf("课程名称", "课程", "courseName", "name").any { value.equals(it, true) } } == true) {
                val parsed = runCatching { parseRows(tabular, "HTML 列表") }.getOrNull()
                if (parsed != null) { result += parsed.courses; warnings += parsed.warnings; continue }
            }
            val headers = mutableMapOf<Int, Int>()
            // Expand rowspans/colspans so day inference remains correct in old Zhengfang tables.
            val occupied = mutableMapOf<Int, Int>()
            var sectionRow = 0
            for (tr in table.select("tr").filter { it.closest("table") == table }) {
                var col = 0
                val nextOccupied = occupied.mapValues { it.value - 1 }.filterValues { it > 0 }.toMutableMap()
                val cells = tr.children().filter { it.tagName() in listOf("td", "th") }
                val firstText = cells.firstOrNull()?.text().orEmpty()
                val explicitSection = Regex("第?(\\d{1,2})节").find(firstText)?.groupValues?.get(1)?.toIntOrNull()
                if (Regex("大节|第[一二三四五六七八九十].*节|^[一二三四五六七八九十]$").containsMatchIn(firstText)) sectionRow = 0
                else if (explicitSection != null) sectionRow = explicitSection
                else if (headers.isNotEmpty() && cells.any { it.select(".timetable_con").isNotEmpty() || it.html().contains("周") }) sectionRow++
                for (cell in cells) {
                    while ((occupied[col] ?: 0) > 0) col++
                    val colspan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 20) ?: 1
                    val rowspan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 30) ?: 1
                    val headerDay = Regex("^(?:星期|周)([一二三四五六日天])$").matchEntire(cell.text().replace(" ", ""))
                    if (headerDay != null) headers[col] = day(headerDay.value)
                    if (rowspan > 1) for (c in col until col + colspan) nextOccupied[c] = rowspan - 1
                    val chunks = cell.select(".timetable_con, .kbcontent, .kb_content, [id^=kbcontent]").let {
                        if (it.isNotEmpty()) it.toList() else {
                            val pieces = cell.html().split(Regex("(?i)(?:<br\\s*/?>\\s*){2,}|<hr[^>]*>"))
                            if (pieces.size > 1) pieces.filter { part -> part.isNotBlank() }.map { part -> Jsoup.parseBodyFragment(part).body() }
                            else listOf(cell)
                        }
                    }
                    for (container in chunks) for (chunk in container.html().split(Regex("(?i)<hr[^>]*>")).map { if (container.html().contains(Regex("(?i)<hr[^>]*>"))) Jsoup.parseBodyFragment(it).body() else container }) {
                        val content = lines(chunk).replace("(周)", "周").replace("（周）", "周")
                            .replace(Regex("周?[（(]([单双])周[）)]"), "周($1)")
                        if (!content.contains('周') || (!content.contains('节') && sectionRow == 0)) continue
                        try {
                            val course = htmlCourse(chunk, content, headers[col], sectionRow, rowspan)
                            if (course != null) result += course
                        } catch (e: Exception) {
                            if (Regex("\\d+.*周").containsMatchIn(content)) warnings += "页面中的一条课程未识别：${e.message}"
                        }
                    }
                    col += colspan
                }
                occupied.clear(); occupied.putAll(nextOccupied)
            }
        }
        return ImportResult(result.distinctBy { it.key() }, warnings.distinct(), "教务课表 HTML")
    }

    private fun htmlCourse(element: Element, text: String, inferredDay: Int?, inferredSection: Int, rowspan: Int): Course? {
        val weekMatches = Regex("(?:第)?\\d{1,2}(?:\\s*[-~至—]\\s*\\d{1,2})?(?:\\s*[,，、]\\s*\\d{1,2}(?:\\s*[-~至—]\\s*\\d{1,2})?)*\\s*周(?:[（(][单双](?:周)?[）)])?").findAll(text).toList()
        if (weekMatches.isEmpty()) return null
        require(text.lines().count { it.contains("节") && Regex("\\d+.*周").containsMatchIn(it) } <= 1) {
            "一个单元格有多条课程且未分隔，请使用结构化数据或 CSV 导入"
        }
        val weekSet = weekMatches.flatMap { WeekSpec.parse(it.value) }.toSet()
        val dayText = Regex("(?:星期|周)[一二三四五六日天]").find(text)?.value
        val weekday = if (dayText != null) day(dayText) else inferredDay ?: throw IllegalArgumentException("缺少星期信息")
        val sec = Regex("(?:第|[（(])?(\\d{1,2}(?:\\s*[-,，、]\\s*\\d{1,2})*)\\s*节").find(text)?.groupValues?.get(1)
        val range = if (sec != null) sections(sec).singleOrNull() ?: throw IllegalArgumentException("节次不连续，请使用结构化数据导入")
            else if (inferredSection in 1..16) inferredSection..(inferredSection + rowspan - 1)
            else throw IllegalArgumentException("缺少节次信息")
        val title = element.selectFirst(".title, .course-name, .kb_title, b")?.text()?.trim()
        val lineList = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val name = title?.takeIf { it.isNotEmpty() } ?: lineList.firstOrNull { !it.matches(Regex("[上下晚]午|\\d+|第.*节")) } ?: return null
        if (name.contains("星期") || name.contains("课程表")) return null
        fun label(vararg names: String): String {
            for (n in names) {
                val titled = element.select("[title]").firstOrNull { it.attr("title").contains(n) }
                if (titled != null) return titled.text().replace(Regex("^.*?[：:]"), "").trim()
                val line = lineList.firstOrNull { it.startsWith("$n：") || it.startsWith("$n:") }
                if (line != null) return line.substringAfter('：', line.substringAfter(':')).trim()
            }
            return ""
        }
        val timingIndex = lineList.indexOfFirst { it.contains("节") && it.contains("周") }
        val teacher = label("教师", "老师", "任课教师").ifEmpty { if (timingIndex >= 0) lineList.getOrNull(timingIndex + 1).orEmpty() else "" }
        val room = label("上课地点", "教室", "场地").ifEmpty { if (timingIndex >= 0) lineList.getOrNull(timingIndex + 2).orEmpty() else "" }
        return validate(Course(name = name, teacher = teacher, room = room, weekday = weekday,
            startSection = range.first, endSection = range.last, weeks = weekSet.sorted().joinToString(","),
            color = Math.floorMod(name.hashCode(), 8)))
    }

    private fun lines(el: Element): String {
        val copy = el.clone()
        copy.select("br").forEach { it.before("\n") }
        copy.select("p, div, span.title").forEach { it.appendText("\n") }
        return copy.wholeText().replace('\u00a0', ' ').trim()
    }
    private fun first(obj: JSONObject, vararg fields: String) = fields.asSequence().map { obj.optString(it, "") }
        .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
    private fun decodeJcor(value: String): String = if (value.matches(Regex("(?:\\d{2})+")))
        value.chunked(2).map { it.toInt() }.joinToString(",") else value

    fun day(input: String): Int {
        val english = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        english.indexOfFirst { it.equals(input.trim(), true) || it.take(3).equals(input.trim(), true) }.takeIf { it >= 0 }?.let { return it + 1 }
        input.toIntOrNull()?.let { require(it in 1..7) { "星期必须在 1-7 范围内" }; return it }
        val str = input.replace("星期", "").replace("周", "").trim()
        val index = listOf("一", "二", "三", "四", "五", "六", "日").indexOf(if (str == "天") "日" else str)
        require(index >= 0) { "无法识别星期：$input" }; return index + 1
    }

    fun sections(input: String): List<IntRange> {
        val clean = input.replace(Regex("[第节()（）\\[\\]\\s]"), "").replace('，', ',').replace('、', ',')
            .replace('–', '-').replace('—', '-').replace('~', '-')
        val set = linkedSetOf<Int>()
        for (p in clean.split(',')) {
            val m = Regex("^(\\d+)(?:-(\\d+))?$").matchEntire(p) ?: throw IllegalArgumentException("无法识别节次：$input")
            val start = m.groupValues[1].toInt(); val end = m.groupValues[2].toIntOrNull() ?: start
            require(start in 1..16 && end in start..16) { "节次应在 1-16 范围内" }; set += start..end
        }
        val groups = mutableListOf<IntRange>()
        var start = set.minOrNull() ?: throw IllegalArgumentException("缺少节次")
        var end = start
        for (n in set.sorted().drop(1)) {
            if (n == end + 1) end = n else { groups += start..end; start = n; end = n }
        }
        groups += start..end; return groups
    }
    fun validate(course: Course): Course {
        require(course.name.isNotBlank() && course.name.length <= 120) { "课程名称为空或过长" }
        require(course.weekday in 1..7) { "星期应在 1-7 范围内" }
        require(course.startSection in 1..16 && course.endSection in course.startSection..16) { "节次应在 1-16 范围内" }
        WeekSpec.parse(course.weeks)
        require(course.teacher.length <= 200 && course.room.length <= 200 && course.note.length <= 2000) { "课程字段过长" }
        return course.copy(name = course.name.trim(), teacher = course.teacher.trim(), room = course.room.trim(), note = course.note.trim(),
            weeks = course.weekSet().sorted().joinToString(","), color = Math.floorMod(course.color, 8))
    }

    internal fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { row += field.toString(); field.setLength(0) }
                (c == '\n' || c == '\r') && !quoted -> {
                    row += field.toString(); field.setLength(0); rows += row; row = mutableListOf()
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                }
                else -> field.append(c)
            }; i++
        }
        require(!quoted) { "CSV 引号不匹配" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        require(rows.size <= 3001) { "课程数量过多" }
        return rows
    }
}
