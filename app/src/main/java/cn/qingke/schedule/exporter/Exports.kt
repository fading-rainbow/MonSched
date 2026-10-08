package cn.qingke.schedule.exporter

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import cn.qingke.schedule.data.*
import cn.qingke.schedule.ui.placeCourses
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter

object CalendarExport {
    fun encode(table: Timetable, courses: List<Course>, slots: List<TimeSlot>, zone: ZoneId = ZoneId.systemDefault()): String {
        require(courses.isNotEmpty()) { "当前课表没有课程" }
        require(table.startDateConfirmed) { "请先在学期设置中确认第一周的周一，再导出日历" }
        val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val stamp = utc.format(Instant.now())
        fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//QingKe//Schedule 1.0//ZH", "CALSCALE:GREGORIAN", "X-WR-CALNAME:${escape(table.name)}")
        for (course in courses) {
            val start = slots.firstOrNull { it.section == course.startSection }?.start ?: throw IllegalArgumentException("课程缺少开始时间，请设置作息")
            val end = slots.firstOrNull { it.section == course.endSection }?.end ?: throw IllegalArgumentException("课程缺少结束时间，请设置作息")
            for (week in course.weekSet().sorted()) {
                val date = table.monday(week).plusDays((course.weekday - 1).toLong())
                lines += listOf("BEGIN:VEVENT", "UID:qingke-${table.id}-${course.id}-$week@local", "DTSTAMP:$stamp",
                    "DTSTART:${utc.format(date.atTime(LocalTime.parse(start)).atZone(zone).toInstant())}",
                    "DTEND:${utc.format(date.atTime(LocalTime.parse(end)).atZone(zone).toInstant())}",
                    "SUMMARY:${escape(course.name)}", "LOCATION:${escape(course.room)}",
                    "DESCRIPTION:${escape("教师：${course.teacher}\n第 $week 周，第 ${course.startSection}-${course.endSection} 节\n${course.note}")}", "END:VEVENT")
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }
    private fun fold(line: String): String {
        val out = StringBuilder(); var bytes = 0
        val iterator = line.codePoints().iterator()
        while (iterator.hasNext()) {
            val value = String(Character.toChars(iterator.nextInt())); val length = value.toByteArray(Charsets.UTF_8).size
            if (bytes + length > 75) { out.append("\r\n "); bytes = 1 }
            out.append(value); bytes += length
        }
        return out.toString()
    }
}

object TimetableImage {
    fun png(table: Timetable, courses: List<Course>, slots: List<TimeSlot>, week: Int): ByteArray {
        val days = if (table.showWeekend) 7 else 5
        val w = 1480; val gutter = 80f; val header = 210f; val row = 122f; val margin = 30f
        val h = (header + row * table.sectionCount + 70).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(247, 248, 243))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 56, 46); textSize = 32f }
        text.typeface = Typeface.create("sans-serif", Typeface.BOLD); text.textSize = 56f
        canvas.drawText("MonSched · ${table.name}", margin, 80f, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 30f
        val monday = table.monday(week)
        canvas.drawText("第 $week 周    $monday — ${monday.plusDays(6)}", margin, 128f, text)
        val width = (w - margin * 2 - gutter) / days
        val colors = listOf(0xFFDCEBDD, 0xFFE6E4F6, 0xFFF6E6CE, 0xFFD9EAF4, 0xFFF3DDE5, 0xFFE2E9CB, 0xFFDCE8E7, 0xFFE8E2D7)
        for (d in 1..days) {
            val x = margin + gutter + width * (d - 1)
            text.textSize = 27f
            canvas.drawText("周${listOf("一", "二", "三", "四", "五", "六", "日")[d - 1]}  ${monday.plusDays((d - 1).toLong()).dayOfMonth}", x + 14, 182f, text)
        }
        for (s in 1..table.sectionCount) {
            val y = header + (s - 1) * row
            text.textSize = 30f; canvas.drawText("$s", margin + 10, y + 38, text)
            text.textSize = 17f
            val slot = slots.firstOrNull { it.section == s }
            canvas.drawText(slot?.start.orEmpty(), margin, y + 66, text); canvas.drawText(slot?.end.orEmpty(), margin, y + 90, text)
            for (d in 1..days) {
                paint.color = Color.rgb(234, 240, 231)
                val x = margin + gutter + width * (d - 1)
                canvas.drawRoundRect(x + 3, y + 3, x + width - 3, y + row - 3, 16f, 16f, paint)
            }
        }
        for (d in 1..days) {
            val visible = courses.filter { it.weekday == d && (it.occurs(week) || table.showOtherWeeks) }
            for ((c, lane, lanes) in placeCourses(visible)) {
                val cw = width / lanes; val x = margin + gutter + width * (d - 1) + cw * lane; val y = header + (c.startSection - 1) * row
                val height = row * (c.endSection - c.startSection + 1)
                paint.color = if (c.occurs(week)) colors[Math.floorMod(c.color, colors.size)].toInt() else Color.LTGRAY
                canvas.drawRoundRect(x + 3, y + 3, x + cw - 3, y + height - 3, 16f, 16f, paint)
                canvas.save(); canvas.clipRect(x + 10, y + 10, x + cw - 10, y + height - 10)
                val content = "${c.name}\n\n${c.room}" + if (!c.occurs(week)) "\n非本周" else ""
                text.textSize = if (lanes > 1) 23f else 28f; text.typeface = Typeface.DEFAULT
                val layout = StaticLayout.Builder.obtain(content, 0, content.length, text, (cw - 24).toInt().coerceAtLeast(5))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
                canvas.translate(x + 12, y + 14); layout.draw(canvas); canvas.restore()
            }
        }
        text.textSize = 22f
        canvas.drawText("MonSched · 本地课程表    作息与课程安排请以学校通知为准", margin, h - 25f, text)
        return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
    }
}
