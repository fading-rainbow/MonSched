package cn.qingke.schedule.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.qingke.schedule.data.*
import cn.qingke.schedule.BuildConfig
import cn.qingke.schedule.widget.DayAgendaWidget
import cn.qingke.schedule.importer.SchoolConnection
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable fun WeekScreen(table: Timetable, courses: List<Course>, slots: List<TimeSlot>, week: Int,
    now: LocalDateTime, onWeek: (Int) -> Unit, onChoose: () -> Unit, onCourse: (Course) -> Unit,
    onAdd: (Int, Int) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onSemester: () -> Unit) {
    val current = table.currentWeek(now.toLocalDate())
    val monday = table.monday(week)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onChoose)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 $week 周", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Outlined.ExpandMore, "选择周次", Modifier.padding(start = 4.dp))
                }
                Text("${monday.format(DateTimeFormatter.ofPattern("M月d日"))} — ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("M月d日"))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (week != current && current in 1..table.weekCount) TextButton(onClick = { onWeek(current) }) { Text("本周") }
            IconButton(onClick = { onWeek(week - 1) }, enabled = week > 1) { Icon(Icons.Outlined.ChevronLeft, "上一周") }
            IconButton(onClick = { onWeek(week + 1) }, enabled = week < table.weekCount) { Icon(Icons.Outlined.ChevronRight, "下一周") }
        }
        if (!table.startDateConfirmed || current !in 1..table.weekCount) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onSemester).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (!table.startDateConfirmed) "请设置开学日期，确认当前周与提醒" else if (current < 1) "学期尚未开始 · 检查开学日期" else "学期已结束 · 检查学期设置", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
        }
        if (courses.isEmpty()) {
            EmptyTimetable(onImport, { onAdd(1, 1) }, onSemester)
        } else {
            val displayed = courses.filter { (it.occurs(week) || table.showOtherWeeks) && (table.showWeekend || it.weekday <= 5) }
            if (displayed.isEmpty()) Text("这一周没有课程，可以左右切换周次", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val hiddenWeekend = !table.showWeekend && courses.any { it.weekday > 5 && it.occurs(week) }
            if (hiddenWeekend) Text("本周还有周末课程，可在设置中显示", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            WeekGrid(table, displayed, slots, week, now.toLocalDate(), onCourse, onAdd, onWeek)
        }
    }
}

@Composable private fun ColumnScope.EmptyTimetable(onImport: () -> Unit, onAdd: () -> Unit, onSemester: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(108.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp)); Text("你的课表，从这里开始", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp)); Text("从学校教务或文件导入课表，\n或者手动添加第一门课程。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp)); Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("导入课表") }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) { Text("手动添加课程") }
        TextButton(onClick = onSemester) { Text("先设置开学日期与学期") }
    }
}

data class CoursePlacement(val course: Course, val lane: Int, val lanes: Int)
fun placeCourses(courses: List<Course>): List<CoursePlacement> {
    val groups = mutableListOf<MutableList<Course>>()
    for (c in courses.sortedWith(compareBy<Course> { it.startSection }.thenBy { it.endSection })) {
        val last = groups.lastOrNull()
        if (last == null || c.startSection > last.maxOf { it.endSection }) groups += mutableListOf(c) else last += c
    }
    return groups.flatMap { group ->
        val ends = mutableListOf<Int>(); val placed = mutableListOf<Pair<Course, Int>>()
        for (c in group) {
            var lane = ends.indexOfFirst { it < c.startSection }
            if (lane < 0) { lane = ends.size; ends += c.endSection } else ends[lane] = c.endSection
            placed += c to lane
        }
        placed.map { (c, lane) -> CoursePlacement(c, lane, ends.size) }
    }
}

@Composable private fun ColumnScope.WeekGrid(table: Timetable, courses: List<Course>, slots: List<TimeSlot>, week: Int,
    today: LocalDate, onCourse: (Course) -> Unit, onAdd: (Int, Int) -> Unit, onWeek: (Int) -> Unit) {
    val days = if (table.showWeekend) 7 else 5
    val gutter = 36.dp
    val rowHeight = 58.dp
    var dragged by remember { mutableFloatStateOf(0f) }
    Column(Modifier.weight(1f).padding(horizontal = 8.dp).pointerInput(week, table.id) {
        detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
            if (abs(dragged) > 90) onWeek((week + if (dragged < 0) 1 else -1).coerceIn(1, table.weekCount))
        }) { change, amount -> change.consume(); dragged += amount }
    }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Box(Modifier.width(gutter).height(46.dp), contentAlignment = Alignment.Center) {
                Text("${table.monday(week).monthValue}\n月", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (1..days).forEach { d ->
                val date = table.monday(week).plusDays((d - 1).toLong())
                val active = date == today
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                    .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("周${Days[d - 1]}", fontSize = 10.sp, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date.dayOfMonth}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            val width = (maxWidth - gutter) / days
            Box(Modifier.fillMaxWidth().height(rowHeight * table.sectionCount)) {
                (1..table.sectionCount).forEach { s ->
                    Row(Modifier.offset(y = rowHeight * (s - 1)).height(rowHeight).fillMaxWidth()) {
                        Column(Modifier.width(gutter).fillMaxHeight().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$s", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val slot = slots.firstOrNull { it.section == s }
                            Text(slot?.start.orEmpty(), fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(slot?.end.orEmpty(), fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (1..days).forEach { d ->
                            Box(Modifier.width(width).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f)).clickable { onAdd(d, s) }
                                .semantics { contentDescription = "周${Days[d - 1]}第${s}节添加课程" })
                        }
                    }
                }
                (1..days).forEach { d ->
                    placeCourses(courses.filter { it.weekday == d }.sortedByDescending { it.occurs(week) }).forEach { placement ->
                        val c = placement.course; val actual = c.occurs(week)
                        val laneWidth = width / placement.lanes
                        Surface(onClick = { onCourse(c) }, color = if (actual) courseColor(c.color) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.offset(x = gutter + width * (d - 1) + laneWidth * placement.lane, y = rowHeight * (c.startSection - 1))
                                .width(laneWidth).height(rowHeight * (c.endSection - c.startSection + 1)).padding(1.dp)) {
                            Column(Modifier.padding(horizontal = if (placement.lanes > 1) 2.dp else 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(c.name, fontSize = if (placement.lanes > 1) 9.sp else 11.sp, lineHeight = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = if (c.endSection > c.startSection) 4 else 2, overflow = TextOverflow.Ellipsis,
                                    color = if (actual) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (c.room.isNotBlank() && c.endSection > c.startSection) Text(c.room, fontSize = 9.sp, lineHeight = 12.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!actual) Text("非本周", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun TodayScreen(table: Timetable, courses: List<Course>, slots: List<TimeSlot>, now: LocalDateTime, onCourse: (Course) -> Unit, onAdd: () -> Unit, onSemester: () -> Unit) {
    val week = table.currentWeek(now.toLocalDate())
    val list = if (table.startDateConfirmed && week in 1..table.weekCount) courses.filter { it.occurs(week) && it.weekday == now.dayOfWeek.value }.sortedBy { it.startSection } else emptyList()
    fun start(c: Course) = slots.firstOrNull { it.section == c.startSection }?.start?.let(LocalTime::parse)
    fun end(c: Course) = slots.firstOrNull { it.section == c.endSection }?.end?.let(LocalTime::parse)
    val current = list.firstOrNull { start(it)?.let { t -> now.toLocalTime() >= t } == true && end(it)?.let { t -> now.toLocalTime() < t } == true }
    val next = current ?: list.firstOrNull { start(it)?.let { t -> t > now.toLocalTime() } == true }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(now.format(DateTimeFormatter.ofPattern("M月d日")) + " · 周${Days[now.dayOfWeek.value - 1]}", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(if (!table.startDateConfirmed) "先设置学期开学日期" else if (week in 1..table.weekCount) "第 $week 周 · 今天 ${list.size} 个上课时段" else if (week < 1) "学期尚未开始" else "学期已结束",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().then(if (next != null) Modifier.clickable { onCourse(next) } else Modifier)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (!table.startDateConfirmed) "学期日期尚未确认" else if (current != null) "正在上课" else if (next != null) "下一节课" else if (list.isEmpty()) "今天没有课程" else "今日课程已结束",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(next?.name ?: if (!table.startDateConfirmed) "从正确的第一周开始" else "留一点时间给自己", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (next != null) {
                        Text("${start(next)} — ${end(next)} · ${next.room.ifBlank { "教室未填写" }}", style = MaterialTheme.typography.bodyMedium)
                        if (current == null) {
                            val minutes = Duration.between(now.toLocalTime(), start(next)).toMinutes().coerceAtLeast(0)
                            Text(if (minutes < 60) "还有 $minutes 分钟" else "还有 ${minutes / 60} 小时 ${minutes % 60} 分钟", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (!table.startDateConfirmed) Button(onClick = onSemester) { Text("设置开学日期") }
                    else Text("课程安排和提醒都保存在手机本地。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("今日安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp)) }
        items(list, key = { it.id }) { c ->
            Surface(onClick = { onCourse(c) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(57.dp)) { Text(start(c).toString().takeUnless { it == "null" } ?: "—", fontWeight = FontWeight.SemiBold); Text(end(c).toString().takeUnless { it == "null" } ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box(Modifier.width(4.dp).height(52.dp).clip(CircleShape).background(courseColor(c.color)))
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text("${c.room.ifBlank { "教室未填写" }} · 第 ${c.startSection}-${c.endSection} 节", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.teacher.isNotBlank()) Text(c.teacher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (end(c)?.let { it <= now.toLocalTime() } == true) Icon(Icons.Outlined.CheckCircle, "已结束", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (list.isEmpty()) item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("添加课程") } }
    }
}

@Composable fun ImportScreen(table: Timetable, prefs: Preferences, onWeb: () -> Unit, onFile: () -> Unit, onTemplate: () -> Unit, onDemo: () -> Unit, onUrl: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("导入你的课程", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("导入到「${table.name}」", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.School, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text(prefs.schoolName.ifBlank { "选择你的学校" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${SchoolConnection.label(prefs.schoolSystem)} · 手机内登录与解析", style = MaterialTheme.typography.bodyMedium)
                Text("1. 登录学校官方教务系统\n2. 进入学生课表，选择学年学期并查询\n3. 读取课表，核对预览后保存", style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp)
                Button(onClick = if (prefs.schoolUrl.isBlank()) onUrl else onWeb, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (prefs.schoolUrl.isBlank()) "设置学校与教务入口" else "登录教务并导入") }
            }
        }
        TextButton(onClick = onUrl, contentPadding = PaddingValues(0.dp)) { Text("切换学校与教务系统 · ${android.net.Uri.parse(prefs.schoolUrl).host ?: "点击设置"}", style = MaterialTheme.typography.bodySmall); Icon(Icons.Outlined.Edit, null, Modifier.padding(start = 6.dp).size(14.dp)) }
        Text("从文件导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("支持 CSV、Excel XLSX、JSON、HTML 和 ICS 日历。不同学校都可以使用通用模板导入；所有导入先预览再保存。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Excel 使用模板列；ICS 需先确认开学日期与实际作息，用于匹配周次和节次。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("选择课表文件") }
                TextButton(onClick = onTemplate) { Text("保存 CSV 导入模板") }
            }
        }
        TextButton(onClick = onDemo) { Text("创建示例课表，体验完整界面") }
        Text("登录信息只用于学校页面。退出导入会清除登录会话，课表数据保存在本机；更新课表时重新登录即可。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), content = content) }
}
@Composable private fun SettingAction(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SettingsScreen(table: Timetable, prefs: Preferences, timetableCount: Int,
    onUpdateTable: (Timetable) -> Unit, onPrefs: (Preferences) -> Unit, onSemester: () -> Unit,
    onSlots: () -> Unit, onReminders: (Boolean) -> Unit, onBackup: () -> Unit, onCsv: () -> Unit,
    onCalendar: () -> Unit, onRestore: () -> Unit, onDeleteTable: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var preciseUpdates by remember { mutableStateOf(DayAgendaWidget.preciseUpdatesAllowed(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) preciseUpdates = DayAgendaWidget.preciseUpdatesAllowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("课表设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        SettingsGroup("学期与作息") {
            SettingAction(table.name, (if (!table.startDateConfirmed) "开学日期待确认 · " else "") + "第一周周一 ${table.startDate} · ${table.weekCount} 周 · 每天 ${table.sectionCount} 节", onSemester)
            SettingAction("作息时间", "请按学校实际作息设置，用于今日课程与提醒", onSlots)
        }
        SettingsGroup("显示") {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("显示周六、周日", modifier = Modifier.weight(1f)); Switch(table.showWeekend, { onUpdateTable(table.copy(showWeekend = it)) })
            }
            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("显示非本周课程", modifier = Modifier.weight(1f)); Switch(table.showOtherWeeks, { onUpdateTable(table.copy(showOtherWeeks = it)) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) -> FilterChip(prefs.theme == mode, { onPrefs(prefs.copy(theme = mode)) }, label = { Text(label) }) }
            }
        }
        SettingsGroup("上课提醒") {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("启用课前提醒"); Text(if (!table.startDateConfirmed) "请先确认学期开学日期" else "提醒当前选中的课表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(prefs.reminder, onReminders)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { lead -> FilterChip(prefs.leadMinutes == lead, { onPrefs(prefs.copy(leadMinutes = lead)) }, label = { Text("${lead}分") }) }
            }
            Text("提醒时间可能受系统省电策略影响。请检查通知权限，并允许 MonSched 在后台运行。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        }
        SettingsGroup("备份与分享") {
            SettingAction("导出完整备份", "包含全部课表、课程、学期设置和作息", onBackup)
            SettingAction("导出当前课表 CSV", "便于编辑、分享和再次导入", onCsv)
            SettingAction("导出日历文件", "将当前课表导入手机或电脑日历", onCalendar)
            SettingAction("恢复课表备份", "兼容原清课备份，恢复为新课表", onRestore)
        }
        SettingsGroup("桌面小组件") {
            Text("长按桌面空白处 → 小组件 → MonSched → 今天／明天 2×2。点顶部切换日期，在卡片区上下滑动；今天已结束的课程自动隐藏，明天显示全天课程。点击日期或课程打开 App，↻ 可立即刷新。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(18.dp))
            if (Build.VERSION.SDK_INT >= 31) SettingAction("下课时准时更新", if (preciseUpdates) "已允许 · 按课程结束时间隐藏卡片" else "允许“闹钟和提醒”后按下课时间更新", {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            })
            Text("请确认学校实际作息。系统后台限制可能延迟刷新，可在手机设置中允许 MonSched 后台运行。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
        SettingsGroup("关于 MonSched") {
            SettingAction("MonSched ${BuildConfig.VERSION_NAME}", "无广告 · 本地存储 · 无需服务器", onPrivacy)
            SettingAction("隐私说明", "查看数据保存与权限用途", onPrivacy)
            Text("开发者 · MonHamed", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            if (timetableCount > 1) SettingAction("删除当前课表", table.name, onDeleteTable)
        }
        Spacer(Modifier.height(12.dp))
    }
}
