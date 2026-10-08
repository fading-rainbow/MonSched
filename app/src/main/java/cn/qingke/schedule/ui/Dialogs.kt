package cn.qingke.schedule.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.*
import java.time.*
import java.time.temporal.TemporalAdjusters

val Days = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable private fun LargeDialog(title: String, onDismiss: () -> Unit, confirm: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 780.dp), shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                content()
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }; Spacer(Modifier.width(8.dp)); confirm()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CourseEditor(table: Timetable, initial: Course?, allCourses: List<Course>, onDismiss: () -> Unit, onSave: (Course) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by remember { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by remember { mutableStateOf(initial?.room.orEmpty()) }
    var day by remember { mutableIntStateOf(initial?.weekday ?: 1) }
    var start by remember { mutableStateOf((initial?.startSection ?: 1).toString()) }
    var end by remember { mutableStateOf((initial?.endSection ?: 2).coerceAtMost(table.sectionCount).toString()) }
    var weeks by remember { mutableStateOf(initial?.let { WeekSpec.format(it.weekSet()) } ?: "1-${table.weekCount}") }
    var color by remember { mutableIntStateOf(initial?.color ?: 0) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = runCatching { WeekSpec.parse(weeks, table.weekCount) }.getOrDefault(emptySet())
    val overlaps = allCourses.filter { c -> c.id != initial?.id && c.weekday == day &&
        c.startSection <= (end.toIntOrNull() ?: 0) && c.endSection >= (start.toIntOrNull() ?: 99) && c.weekSet().intersect(selected).isNotEmpty() }
    LargeDialog(if (initial?.id != null && initial.id != 0L) "编辑课程" else "添加课程", onDismiss,
        confirm = { Button(onClick = {
            try {
                val s = start.toIntOrNull() ?: throw IllegalArgumentException("请输入开始节次")
                val e = end.toIntOrNull() ?: throw IllegalArgumentException("请输入结束节次")
                require(s in 1..table.sectionCount && e in s..table.sectionCount) { "节次应在 1-${table.sectionCount} 范围内，结束不早于开始" }
                val ws = WeekSpec.parse(weeks, table.weekCount)
                onSave(CourseParser.validate(Course(id = initial?.id ?: 0, timetableId = table.id, name = name.trim(),
                    teacher = teacher.trim(), room = room.trim(), weekday = day, startSection = s, endSection = e,
                    weeks = ws.joinToString(","), color = color, note = note.trim())))
            } catch (e: Exception) { error = e.message }
        }) { Text("保存课程") } }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("课程名称 *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(room, { room = it.take(200) }, label = { Text("教室 / 地点") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(teacher, { teacher = it.take(200) }, label = { Text("教师") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("上课星期", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Days.forEachIndexed { i, d ->
                    Surface(onClick = { day = i + 1 }, shape = CircleShape,
                        color = if (day == i + 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Text(d, color = if (day == i + 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, { start = it.filter(Char::isDigit).take(2) }, label = { Text("开始节次") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(end, { end = it.filter(Char::isDigit).take(2) }, label = { Text("结束节次") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(weeks, { weeks = it.take(240) }, label = { Text("上课周次 *") },
                supportingText = { Text("例如 1-16、1-16(单)、1-4,6-12") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全周" to "1-${table.weekCount}", "单周" to "1-${table.weekCount}(单)", "双周" to "1-${table.weekCount}(双)").forEach { (label, value) ->
                    OutlinedButton(onClick = { weeks = value }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(label) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..table.weekCount).forEach { w ->
                    Surface(onClick = { weeks = WeekSpec.format(if (w in selected) selected - w else selected + w) },
                        color = if (w in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Text("$w", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            Text("课程颜色", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CourseColors.forEachIndexed { i, c ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(c).border(if (i == color) 2.dp else 0.dp,
                        MaterialTheme.colorScheme.primary, CircleShape).clickable { color = i }, contentAlignment = Alignment.Center) {
                        if (i == color) Icon(Icons.Outlined.Check, "已选颜色 ${i + 1}", Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFF2F6954))
                    }
                }
            }
            OutlinedTextField(note, { note = it.take(2000) }, label = { Text("备注") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            if (overlaps.isNotEmpty()) Text("与 ${overlaps.map { it.name }.distinct().joinToString("、")} 的部分上课时段重叠，请核对。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable fun CourseDetails(course: Course, slots: List<TimeSlot>, onDismiss: () -> Unit, onEdit: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    val times = "${slots.firstOrNull { it.section == course.startSection }?.start.orEmpty()}–${slots.firstOrNull { it.section == course.endSection }?.end.orEmpty()}"
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(course.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("周${Days[course.weekday - 1]} · 第 ${course.startSection}-${course.endSection} 节 · $times")
            Text("第 ${WeekSpec.format(course.weekSet())} 周")
            Text("教室：${course.room.ifBlank { "未填写" }}")
            Text("教师：${course.teacher.ifBlank { "未填写" }}")
            if (course.note.isNotBlank()) Text(course.note)
            TextButton(onClick = onDuplicate) { Text("添加另一个上课时段") }
            TextButton(onClick = { deleting = true }) { Text("删除此时段", color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { Button(onClick = onEdit) { Text("编辑") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这个上课时段？") },
        text = { Text("${course.name} 的其他上课时段会保留。") },
        confirmButton = { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable fun TimetableEditor(table: Timetable, courses: List<Course>, onDismiss: () -> Unit, onSave: (Timetable) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(table.name) }
    var date by remember { mutableStateOf(LocalDate.parse(table.startDate)) }
    var weeks by remember { mutableStateOf(table.weekCount.toString()) }
    var sections by remember { mutableStateOf(table.sectionCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    LargeDialog("学期设置", onDismiss, confirm = { Button(onClick = {
        try {
            require(name.isNotBlank()) { "课表名称不能为空" }
            val w = weeks.toIntOrNull() ?: 0; val s = sections.toIntOrNull() ?: 0
            require(w in 1..40 && s in 1..16) { "学期周数应在 1-40，每天节数应在 1-16" }
            require(courses.all { c -> c.endSection <= s && c.weekSet().all { it <= w } }) { "已有课程超出这个范围，请先调整课程" }
            onSave(table.copy(name = name.trim(), startDate = date.toString(), weekCount = w, sectionCount = s, startDateConfirmed = true))
        } catch (e: Exception) { error = e.message }
    }) { Text("保存设置") } }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("课表名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("第一周的周一", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) },
                    date.year, date.monthValue - 1, date.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(date.toString()) }
            Text("请选择学校第一周所在的日期，自动对齐到该周周一。此日期决定当前周和提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(weeks, { weeks = it.filter(Char::isDigit).take(2) }, label = { Text("学期周数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(sections, { sections = it.filter(Char::isDigit).take(2) }, label = { Text("每天节数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable fun SlotsEditor(table: Timetable, existing: List<TimeSlot>, onDismiss: () -> Unit, onSave: (List<TimeSlot>) -> Unit) {
    val context = LocalContext.current
    var slots by remember { mutableStateOf(SchoolTimes.defaults(table.id).map { fallback -> existing.firstOrNull { it.timetableId == table.id && it.section == fallback.section } ?: fallback }.take(table.sectionCount)) }
    var error by remember { mutableStateOf<String?>(null) }
    LargeDialog("作息时间", onDismiss, confirm = { Button(onClick = {
        try {
            require(slots.all { LocalTime.parse(it.end) > LocalTime.parse(it.start) }) { "每节的结束时间必须晚于开始时间" }
            require(slots.zipWithNext().all { (a, b) -> LocalTime.parse(a.end) <= LocalTime.parse(b.start) }) { "相邻节次的时间不能重叠" }
            onSave(slots)
        } catch (e: Exception) { error = e.message }
    }) { Text("保存作息") } }) {
        Text("初始时间仅为示例，请按学校实际作息修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
            slots.forEachIndexed { index, slot ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("第 ${slot.section} 节", style = MaterialTheme.typography.bodyMedium)
                    fun pick(start: Boolean) {
                        val value = LocalTime.parse(if (start) slot.start else slot.end)
                        TimePickerDialog(context, { _, h, m ->
                            val time = LocalTime.of(h, m).toString()
                            slots = slots.toMutableList().also { it[index] = if (start) slot.copy(start = time) else slot.copy(end = time) }
                        }, value.hour, value.minute, true).show()
                    }
                    TextButton(onClick = { pick(true) }) { Text(slot.start) }
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { pick(false) }) { Text(slot.end) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            }
            TextButton(onClick = { slots = SchoolTimes.defaults(table.id).take(table.sectionCount) }) { Text("重置为示例作息") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun WeekChooser(table: Timetable, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择周次") }, text = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..table.weekCount).forEach { w -> FilterChip(selected = w == selected, onClick = { onPick(w) }, label = { Text("$w") }) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable fun ImportPreview(result: ImportResult, table: Timetable, onDismiss: () -> Unit, onImport: (List<Course>, Boolean) -> Unit) {
    var selected by remember { mutableStateOf(result.courses.indices.toSet()) }
    var replace by remember { mutableStateOf(false) }
    LargeDialog("导入预览", onDismiss, confirm = {
        Button(onClick = { onImport(result.courses.filterIndexed { i, _ -> i in selected }, replace) }, enabled = selected.isNotEmpty()) { Text("导入 ${selected.size} 个时段") }
    }) {
        Text("导入到「${table.name}」", style = MaterialTheme.typography.titleSmall)
        Text("第一周周一：${table.startDate}" + if (!table.startDateConfirmed) " · 尚未确认，请在学期设置中修改" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${result.source} · ${result.courses.map { it.name }.distinct().size} 门课程 · ${result.courses.size} 个时段",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("请核对周次、节次和教室，再确认保存。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        if (result.warnings.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text("${result.warnings.size} 条记录未识别，可能导致课程不完整", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected.size == result.courses.size, { selected = if (it) result.courses.indices.toSet() else emptySet() })
            Text("全选", modifier = Modifier.weight(1f))
            Text("${selected.size}/${result.courses.size}", style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(result.courses) { i, c ->
                Surface(color = courseColor(c.color).copy(alpha = .7f), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { selected = if (i in selected) selected - i else selected + i }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i in selected, { selected = if (it) selected + i else selected - i })
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text("周${Days[c.weekday - 1]} ${c.startSection}-${c.endSection}节 · ${WeekSpec.format(c.weekSet())}周", style = MaterialTheme.typography.bodySmall)
                            Text(listOf(c.room, c.teacher).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "教室 / 教师未填写" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replace = !replace }.padding(top = 8.dp)) {
            Checkbox(replace, { replace = it })
            Column {
                Text("替换当前课表的课程", style = MaterialTheme.typography.bodyMedium)
                Text(if (replace) "确认后清除当前课表原有课程" else "当前为追加导入，自动跳过重复课程", style = MaterialTheme.typography.bodySmall,
                    color = if (replace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
