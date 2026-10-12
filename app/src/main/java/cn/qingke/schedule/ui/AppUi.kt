package cn.qingke.schedule.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import cn.qingke.schedule.data.*
import cn.qingke.schedule.importer.*
import cn.qingke.schedule.exporter.*
import cn.qingke.schedule.sync.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@Composable fun QingKeRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val table by vm.active.collectAsStateWithLifecycle()
    val tables by vm.timetables.collectAsStateWithLifecycle()
    val loadedCourses by vm.courses.collectAsStateWithLifecycle()
    val loadedSlots by vm.slots.collectAsStateWithLifecycle()
    val courses = loadedCourses.filter { it.timetableId == table?.id }
    val slots = loadedSlots.filter { it.timetableId == table?.id }
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if ((context as? Activity)?.intent?.getBooleanExtra("openSync", false) == true) 3 else 0) }
    val syncConnections by vm.syncConnections.collectAsStateWithLifecycle()
    val syncConnection = syncConnections.firstOrNull { it.timetableId == table?.id }
    var pendingSyncToken by rememberSaveable { mutableStateOf<String?>(null) }
    var forgetSync by remember { mutableStateOf(false) }
    var reviewSync by remember { mutableStateOf(false) }
    var week by rememberSaveable { mutableIntStateOf(1) }
    var weekTableId by rememberSaveable { mutableLongStateOf(-1) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var chooseWeek by remember { mutableStateOf(false) }
    var tableMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var createTable by remember { mutableStateOf(false) }
    var tableName by remember { mutableStateOf("") }
    var semester by remember { mutableStateOf(false) }
    var editSlots by remember { mutableStateOf(false) }
    var slotSection by remember { mutableIntStateOf(1) }
    var duplicatePlan by remember { mutableStateOf<DuplicatePlan?>(null) }
    var editor by remember { mutableStateOf<Course?>(null) }
    var details by remember { mutableStateOf<Course?>(null) }
    var preview by remember { mutableStateOf<ImportResult?>(null) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var backupCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var schoolNameText by remember { mutableStateOf("") }
    var schoolSystemText by remember { mutableStateOf("zhengfang") }
    var allowHttpText by remember { mutableStateOf(false) }
    var deleteTable by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scope.launch { SyncScheduler.maintain(context) } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    LaunchedEffect(table?.id) { table?.let { if (weekTableId != it.id) { week = it.currentWeek().coerceIn(1, it.weekCount); weekTableId = it.id } } }
    LaunchedEffect(table?.weekCount) { table?.let { week = week.coerceIn(1, it.weekCount) } }
    BackHandler(tab != 0 && editor == null && details == null && preview == null) { tab = 0 }

    fun add(day: Int = 1, section: Int = 1) {
        table?.let { t -> editor = Course(timetableId = t.id, name = "", weekday = day, startSection = section,
            endSection = (section + 1).coerceAtMost(t.sectionCount), weeks = (1..t.weekCount).joinToString(",")) }
    }
    fun openSlots(section: Int = 1) { slotSection = section; editSlots = true }
    fun write(uri: Uri?, produce: suspend () -> ByteArray) {
        if (uri == null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = produce()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalArgumentException("无法写入所选位置")
                }
                snackbar.showSnackbar("文件已保存")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "文件保存失败" }
            finally { busy = false }
        }
    }
    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开文件")
        val bytes = stream.use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 5 * 1024 * 1024) { "请选择 5 MB 以内的课表文件" }
                out.write(buffer, 0, n)
            }; out.toByteArray()
        }
        bytes
    }
    suspend fun read(uri: Uri): String {
        val bytes = readBytes(uri); val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) bytes.toString(charset("GB18030")) else utf8.removePrefix("\uFEFF")
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setPreferences(vm.preferences.value.copy(reminder = granted))
        if (!granted) scope.launch { snackbar.showSnackbar("未获得通知权限，可在系统设置中开启") }
    }
    val syncNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar("同步仍可使用，登录失效和调课结果请在设置中查看") }
    }
    val importWeb = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            busy = true
            scope.launch {
                val file = File(context.cacheDir, "pending-import.json")
                pendingSyncToken = result.data?.getStringExtra("syncToken")
                try { preview = withContext(Dispatchers.IO) { CourseParser.parse(file.readText()) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    error = e.message ?: "课表读取失败"
                    pendingSyncToken?.let { withContext(Dispatchers.IO) { SyncVault(context).delete(it) } }; pendingSyncToken = null
                }
                finally { file.delete(); busy = false }
            }
        }
    }
    fun openSchool(configureSync: Boolean) {
        val target = table ?: return
        scope.launch {
            val stored = if (configureSync && syncConnection != null) withContext(Dispatchers.IO) {
                runCatching { SyncVault(context).load(syncConnection.sourceId) }.getOrNull()
            } else null
            val entry = stored?.loginUrl ?: prefs.schoolUrl
            if (entry.isBlank()) { urlText = prefs.schoolUrl; schoolNameText = prefs.schoolName; schoolSystemText = prefs.schoolSystem; allowHttpText = prefs.allowHttp; urlDialog = true; return@launch }
            importWeb.launch(Intent(context, WebImportActivity::class.java).putExtra("url", entry)
                .putExtra("system", stored?.system ?: prefs.schoolSystem).putExtra("allowHttp", stored?.allowHttp ?: prefs.allowHttp)
                .putExtra("timetableId", target.id).putExtra("configureSync", configureSync)
                .putExtra("schoolName", if (stored != null) syncConnection?.schoolName else prefs.schoolName))
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val bytes = readBytes(uri)
                    val target = table ?: throw IllegalArgumentException("课表尚未加载")
                    preview = withContext(Dispatchers.Default) { ImportFiles.parse(bytes, target, slots) }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "文件导入失败" }
                finally { busy = false }
            }
        }
    }
    val backupFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        write(uri) { vm.exportBackup().toByteArray(Charsets.UTF_8) }
    }
    val csvFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        write(uri) { BackupCodec.csv(courses).toByteArray(Charsets.UTF_8) }
    }
    val calendarFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        write(uri) { CalendarExport.encode(table ?: throw IllegalArgumentException("课表尚未加载"), courses, slots).toByteArray(Charsets.UTF_8) }
    }
    val imageFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        write(uri) { TimetableImage.png(table ?: throw IllegalArgumentException("课表尚未加载"), courses, slots, week) }
    }
    val templateFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        write(uri) { ("\uFEFF课程名称,教师,教室,星期,开始节,结束节,周次,备注\n" +
            "示例课程,示例教师,A201,1,1,2,1-16,此行仅为示例，请替换\n" +
            "单双周示例,示例教师,B302,3,3,4,1-16(单),此行仅为示例，请替换\n").toByteArray(Charsets.UTF_8) }
    }
    val restoreFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val text = read(uri)
                    backupCount = withContext(Dispatchers.Default) { BackupCodec.decode(text).size }
                    pendingBackup = text
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "无法读取备份" }
                finally { busy = false }
            }
        }
    }
    val t = table
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MonSched", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { tableMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(t?.name ?: "加载课表…", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ExpandMore, "切换课表", Modifier.size(18.dp))
                }
                DropdownMenu(tableMenu, { tableMenu = false }) {
                    tables.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { vm.switch(item.id); tableMenu = false },
                        trailingIcon = { if (t?.id == item.id) Icon(Icons.Outlined.Check, null) }) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("新建课表") }, onClick = { tableMenu = false; tableName = ""; createTable = true }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
                }
            }
            IconButton(onClick = { add() }, enabled = t != null) { Icon(Icons.Outlined.Add, "添加课程") }
            Box {
                IconButton(onClick = { moreMenu = true }, enabled = t != null) { Icon(Icons.Outlined.MoreVert, "更多功能") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("导出第 $week 周图片") }, onClick = { moreMenu = false; imageFile.launch("MonSched-${t?.name}-第${week}周.png") })
                    DropdownMenuItem(text = { Text("导出完整备份") }, onClick = { moreMenu = false; backupFile.launch("MonSched备份-${LocalDate.now()}.json") })
                    DropdownMenuItem(text = { Text("学期设置") }, onClick = { moreMenu = false; semester = true })
                    DropdownMenuItem(text = { Text("自定义上课时间") }, onClick = { moreMenu = false; openSlots() })
                    DropdownMenuItem(text = { Text("整理重复课程") }, onClick = { moreMenu = false; duplicatePlan = CourseDuplicates.plan(courses) })
                }
            }
        } },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            val labels = listOf("课表", "今日", "导入", "设置")
            val icons = listOf(Icons.Outlined.GridView, Icons.Outlined.Today, Icons.Outlined.Download, Icons.Outlined.Settings)
            labels.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i },
                icon = { Icon(icons[i], label) }, label = { Text(label) }) }
        } }, snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (t == null) CircularProgressIndicator(Modifier.align(Alignment.Center)) else {
                Column {
                    if (t.name == "示例课表") Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("示例课表 · 仅供体验，非学校真实课程", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    when (tab) {
                        0 -> WeekScreen(t, courses, slots, week, now, { week = it.coerceIn(1, t.weekCount) }, { chooseWeek = true }, { details = it },
                            { day, section -> add(day, section) }, { tab = 2 }, { imageFile.launch("MonSched-${t.name}-第${week}周.png") }, { semester = true },
                            { section -> openSlots(section) }, { duplicatePlan = CourseDuplicates.plan(courses) })
                        1 -> TodayScreen(t, courses, slots, now, { details = it }, { add(now.dayOfWeek.value) }, { semester = true })
                        2 -> ImportScreen(t, prefs, { openSchool(false) },
                            { importFile.launch(arrayOf("*/*")) }, { templateFile.launch("MonSched导入模板.csv") },
                            { vm.createDemo { tab = 0 } }, { urlText = prefs.schoolUrl; schoolNameText = prefs.schoolName; schoolSystemText = prefs.schoolSystem; allowHttpText = prefs.allowHttp; urlDialog = true })
                        3 -> SettingsScreen(t, prefs, tables.size, vm::update, vm::setPreferences, { semester = true }, { openSlots() },
                            { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else vm.setPreferences(prefs.copy(reminder = enabled))
                            }, { backupFile.launch("MonSched备份-${LocalDate.now()}.json") }, { csvFile.launch("MonSched-${t.name}.csv") },
                            { calendarFile.launch("MonSched-${t.name}.ics") }, { restoreFile.launch(arrayOf("*/*")) }, { deleteTable = true }, { privacy = true },
                            syncConnection, { openSchool(true) }, { vm.setSyncEnabled(t.id, it) }, { vm.syncNow(t.id) },
                            { forgetSync = true }, { reviewSync = true })
                    }
                }
            }
            if (busy) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = .85f)) {
                Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("正在处理…", modifier = Modifier.padding(top = 16.dp)) } }
            }
        }
    }
    if (t != null) {
        if (chooseWeek) WeekChooser(t, week, { chooseWeek = false }, { week = it; chooseWeek = false })
        editor?.let { c -> CourseEditor(t, c, courses, { editor = null }, { vm.save(it); editor = null }) }
        details?.let { c -> CourseDetails(c, slots, { details = null }, { editor = c; details = null },
            { editor = c.copy(id = 0); details = null }, { vm.delete(c); details = null }) }
        if (semester) TimetableEditor(t, courses, { semester = false }, { vm.update(it); semester = false })
        if (editSlots) SlotsEditor(t, slots, { editSlots = false }, { vm.saveSlots(it); editSlots = false }, slotSection)
        duplicatePlan?.let { plan -> DuplicateCoursesDialog(t, plan, busy, { if (!busy) duplicatePlan = null }, {
            busy = true
            val target = plan.groups.firstOrNull()?.firstOrNull()?.timetableId ?: t.id
            vm.mergeDuplicates(target, onFailure = { busy = false }) { busy = false; duplicatePlan = null }
        }) }
        preview?.let { p -> ImportPreview(p, t, {
            if (!busy) {
                preview = null; val token = pendingSyncToken; pendingSyncToken = null
                token?.let { scope.launch(Dispatchers.IO) { SyncVault(context).delete(it) } }
            }
        }, { list, replace ->
            busy = true
            val syncSetup = pendingSyncToken != null
            vm.importCourses(list, replace, pendingSyncToken, onFailure = { busy = false }) {
                busy = false; pendingSyncToken = null; preview = null; tab = 0
                if (syncSetup && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    syncNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }, pendingSyncToken != null, busy, if (pendingSyncToken != null) courses.filter { it.syncSource.isBlank() || it.syncSource == syncConnection?.sourceId } else courses) }
        if (forgetSync) AlertDialog(onDismissRequest = { forgetSync = false }, title = { Text("清除记住的登录信息？") },
            text = { Text("停止每日同步，删除本机加密保存的账号、密码和同步会话。现有课程保留为普通课程。") },
            confirmButton = { TextButton(onClick = { vm.forgetSync(t.id); forgetSync = false }) { Text("清除并停止") } },
            dismissButton = { TextButton(onClick = { forgetSync = false }) { Text("取消") } })
        if (reviewSync && syncConnection != null) {
            val pending = runCatching { SyncSnapshots.decode(syncConnection.pendingSnapshot) }.getOrNull()
            val delta = pending?.let { SyncReconciler.plan(courses, it, syncConnection.sourceId, t.id) }
            AlertDialog(onDismissRequest = { reviewSync = false }, title = { Text("确认官方课表变化") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次读取 ${pending?.size ?: 0} 个官方时段。${delta?.summary.orEmpty()}。")
                    Text(if (pending.isNullOrEmpty()) "官方返回空课表，请先确认学期和学校查询结果。应用后移除已绑定的官方课程，自建课程保留。"
                        else "课程数量明显减少，请核对所选学期。应用后更新官方课程，自建课程保留。")
                    pending?.take(5)?.forEach { Text("${it.name} · 周${it.weekday} 第 ${it.startSection}-${it.endSection} 节", style = MaterialTheme.typography.bodySmall) }
                } }, confirmButton = { TextButton(onClick = { vm.approveSync(t.id); reviewSync = false }, enabled = pending != null && syncConnection.enabled) { Text("确认并应用") } },
                dismissButton = { TextButton(onClick = { reviewSync = false }) { Text("暂不应用") } })
        }
        if (deleteTable) AlertDialog(onDismissRequest = { deleteTable = false }, title = { Text("删除「${t.name}」？") },
            text = { Text("此课表的全部课程和设置会被删除。可先导出完整备份。") },
            confirmButton = { TextButton(onClick = { vm.deleteTimetable(t); deleteTable = false }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTable = false }) { Text("取消") } })
    }
    if (createTable) AlertDialog(onDismissRequest = { createTable = false }, title = { Text("新建课表") },
        text = { OutlinedTextField(tableName, { tableName = it.take(60) }, label = { Text("课表名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.create(tableName); createTable = false; tab = 0 }, enabled = tableName.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = { createTable = false }) { Text("取消") } })
    pendingBackup?.let { text -> AlertDialog(onDismissRequest = { pendingBackup = null }, title = { Text("恢复 $backupCount 张课表？") },
        text = { Text("备份将恢复为新的课表，名称附加“恢复”。已有课表不会被覆盖。") },
        confirmButton = { TextButton(onClick = { vm.restoreBackup(text) { pendingBackup = null; tab = 0 } }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { pendingBackup = null }) { Text("取消") } }) }
    if (urlDialog) AlertDialog(onDismissRequest = { urlDialog = false }, title = { Text("学校与教务系统") },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(schoolNameText, { schoolNameText = it.take(60) }, label = { Text("学校名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var systemMenu by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick = { systemMenu = true }) { Text(SchoolConnection.label(schoolSystemText)) }
                DropdownMenu(systemMenu, { systemMenu = false }) { SchoolConnection.systems.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { schoolSystemText = key; systemMenu = false })
                } }
            }
            OutlinedTextField(urlText, { urlText = it.trim() }, label = { Text("官方登录网址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { schoolNameText = "青岛工学院"; schoolSystemText = "zhengfang"; urlText = SchoolConnection.QIT_URL; allowHttpText = false }) { Text("使用青岛工学院预设") }
            if (urlText.startsWith("http://", true) && !Uri.parse(urlText).host.equals("jw.qit.edu.cn", true)) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allowHttpText, { allowHttpText = it }); Text("该官方入口仅有 HTTP，我了解明文传输风险", style = MaterialTheme.typography.bodySmall)
            }
            Text("填写学校公布的完整网址，优先 HTTPS。正方可读取结构化课表；其他系统读取已打开的课程表页面，无法识别时可使用文件导入。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = {
            if (schoolNameText.isNotBlank() && SchoolConnection.valid(urlText, allowHttp = allowHttpText)) {
                vm.setPreferences(prefs.copy(schoolUrl = urlText, schoolName = schoolNameText.trim(), schoolSystem = schoolSystemText, allowHttp = allowHttpText)); urlDialog = false
            } else error = "请填写学校名称和有效的官方网址；HTTP 入口需确认传输方式"
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("取消") } })
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text("MonSched · 隐私说明") }, text = {
        Text("课程、学期设置和作息保存在手机私有数据库中。没有广告、统计 SDK，也不连接个人服务器。\n\n普通教务导入退出后清除网页会话。只有你主动开启每日同步，才将账号密码、同步会话与课表入口使用 Android Keystore 和 AES-GCM 加密保存在本机，不写入课表备份。可暂停或彻底清除记住的登录信息。\n\n自动填充仅限配置的同一官方入口，不绕过验证码或证书错误。HTTP 入口在网络传输时不加密，请使用可信网络。每日跨日后联网检查，系统后台限制可能延迟。\n\n通知用于主动开启的提醒与同步结果。“闹钟和提醒”权限用于组件准时更新。文件操作通过系统选择器授权。卸载会删除本地数据，请先导出课表备份。")
    }, confirmButton = { TextButton(onClick = { privacy = false }) { Text("知道了") } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("暂时无法完成") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}
