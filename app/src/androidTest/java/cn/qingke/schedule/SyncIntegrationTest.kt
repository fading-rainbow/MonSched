package cn.qingke.schedule

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import cn.qingke.schedule.data.*
import cn.qingke.schedule.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local synthetic school: actual HTTP, cookies, normal form login and real offscreen WebView. */
internal class SyntheticSchool(private val captcha: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0)
    private val executor = Executors.newCachedThreadPool()
    val origin = "http://127.0.0.1:${server.localPort}"
    val loginUrl = "$origin/academic/xtgl/login_slogin.html"
    val pageUrl = "$origin/academic/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151"
    val loginCount = AtomicInteger()
    val queryBody = AtomicReference("")
    @Volatile var data = """{"kbList":[{"kcmc":"Synthetic Math","xm":"Synthetic Teacher","cdmc":"B202","xqj":"2","jcs":"3-4","zcd":"1-4周"}]}"""
    init {
        executor.execute {
            while (!server.isClosed) try {
                val socket = server.accept()
                executor.execute {
                    try { socket.use {
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty().split(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                        val count = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = CharArray(count); var offset = 0
                        while (offset < count) { val n = reader.read(body, offset, count - offset); if (n < 0) break; offset += n }
                        val text = String(body)
                        val path = request.getOrNull(1).orEmpty().substringBefore('?')
                        var status = "200 OK"; var extra = ""; var type = "text/html; charset=utf-8"
                        val authenticated = headers["cookie"].orEmpty().contains("synthetic_session=ok")
                        val response = when {
                            path == "/academic/login" -> {
                                loginCount.incrementAndGet()
                                if (text.contains("account=demo") && text.contains("secret=SYNTHETIC_PASSWORD")) {
                                    status = "302 Found"; extra = "Location: /academic/xtgl/index_initMenu.html\r\nSet-Cookie: synthetic_session=ok; Path=/; HttpOnly\r\n"; ""
                                } else "<p>登录失败</p><input type=password>"
                            }
                            path.endsWith("login_slogin.html") -> """<html><body><form method="POST" action="/academic/login">
                                <input id="yhm" name="account" type="text"><input id="mm" name="secret" type="password">
                                ${if (captcha) "<input id=yzm name=yzm placeholder=验证码>" else ""}<button type="submit">登录</button>
                                </form></body></html>"""
                            path == "/academic/xtgl/index_initMenu.html" -> "<html><body>已登录</body></html>"
                            path.contains("/kbcx/") && !authenticated -> {
                                status = "302 Found"; extra = "Location: /academic/xtgl/login_slogin.html\r\n"; ""
                            }
                            path.endsWith("xskbcx_cxXsKb.html") -> { queryBody.set(text); type = "application/json; charset=utf-8"; data }
                            path.endsWith("xskbcx_cxXskbcxIndex.html") -> """<html><body>
                                <select id="xnm"><option value="2025">2025</option><option value="2026">2026</option></select>
                                <select id="xqm"><option value="12">春季</option><option value="3">秋季</option></select>
                                <table id="kbgrid_table_0"><tr><th>节次</th><th>周一</th></tr></table></body></html>"""
                            else -> "<html><body>synthetic</body></html>"
                        }
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().use { out ->
                            out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra".toByteArray())
                            out.write("Connection: close\r\n\r\n".toByteArray()); out.write(bytes); out.flush()
                        }
                    } } catch (_: Exception) { /* Chromium may cancel prefetch or favicon sockets. */ }
                }
            } catch (_: SocketException) { } catch (_: Exception) { }
        }
    }
    override fun close() { server.close(); executor.shutdownNow() }
}

@RunWith(AndroidJUnit4::class)
class SyncIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(school: SyntheticSchool, cookies: Boolean, action: suspend (SyncConnection, SyncProfile) -> Unit) {
        val dao = AppDatabase.get(context).dao()
        val table = dao.insertTimetable(Timetable(name = "自动同步合成测试", startDate = "2026-09-07", startDateConfirmed = true))
        val profile = SyncProfile(loginUrl = school.loginUrl, pageUrl = school.pageUrl, system = "zhengfang", allowHttp = true,
            username = "demo", password = "SYNTHETIC_PASSWORD", controls = mapOf("xnm" to "2026", "xqm" to "3"),
            cookies = if (cookies) mapOf(school.pageUrl to "synthetic_session=ok") else emptyMap(), year = "2026", term = "3",
            pending = false, timetableId = table, schoolName = "合成学校")
        val connection = SyncConnection(table, profile.id, "合成学校")
        SyncVault(context).save(profile)
        dao.saveSyncConnection(connection)
        dao.insertCourses(listOf(Course(timetableId = table, name = "Synthetic Math", teacher = "Synthetic Teacher", room = "A101",
            weeks = "1,2,3,4", color = 4, note = "用户的笔记", syncSource = profile.id),
            Course(timetableId = table, name = "自建自习", room = "图书馆", note = "必须保留")))
        try { action(connection, profile) } finally {
            SyncCoordinator.forget(context, table)
            dao.timetables().firstOrNull { it.id == table }?.let { dao.deleteTimetable(it) }
        }
    }
    private suspend fun worker(connection: SyncConnection): ListenableWorker.Result =
        TestListenableWorkerBuilder<OfficialSyncWorker>(context).setInputData(workDataOf(
            "tableId" to connection.timetableId, "sourceId" to connection.sourceId, "force" to true)).build().doWork()

    @Test fun encryptedVaultIsAuthenticatedAndNeverSerializesCredentialsInDiagnostics() {
        val vault = SyncVault(context)
        val profile = SyncProfile(loginUrl = "https://school.example.edu/login", pageUrl = "https://school.example.edu/kb",
            system = "generic", allowHttp = false, username = "SYNTHETIC_ACCOUNT", password = "SYNTHETIC_VAULT_PASSWORD",
            cookies = mapOf("https://school.example.edu/kb" to "session=SYNTHETIC_SESSION"))
        val file = File(context.noBackupFilesDir, "school-sync/${profile.id}.enc")
        try {
            vault.save(profile); val first = file.readBytes()
            for (value in listOf(profile.username, profile.password, "SYNTHETIC_SESSION")) {
                assertFalse(first.toString(Charsets.ISO_8859_1).contains(value)); assertFalse(profile.toString().contains(value))
            }
            assertEquals(profile, vault.load(profile.id))
            vault.save(profile); assertFalse(first.contentEquals(file.readBytes()))
            val tampered = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }; file.writeBytes(tampered)
            assertTrue(runCatching { vault.load(profile.id) }.isFailure)
        } finally { vault.delete(profile.id) }
    }
    @Test fun savedSessionSyncsSelectedTermEvenWhenSchoolHasCaptcha() = runBlocking {
        SyntheticSchool(captcha = true).use { school -> fixture(school, true) { connection, _ ->
            assertEquals(ListenableWorker.Result.success(), worker(connection))
            val dao = AppDatabase.get(context).dao(); val courses = dao.courses(connection.timetableId)
            val math = courses.single { it.name == "Synthetic Math" }
            assertEquals("B202", math.room); assertEquals(2, math.weekday); assertEquals(3, math.startSection)
            assertEquals("用户的笔记", math.note); assertEquals(4, math.color)
            assertEquals("必须保留", courses.single { it.name == "自建自习" }.note)
            assertEquals(0, school.loginCount.get()); assertTrue(school.queryBody.get().contains("xnm=2026"))
            assertTrue(school.queryBody.get().contains("xqm=3")); assertEquals("ready", dao.syncConnection(connection.timetableId)?.status)
            val backup = cn.qingke.schedule.importer.BackupCodec.encode(listOf(cn.qingke.schedule.importer.BackupCodec.Table(
                dao.timetables().first { it.id == connection.timetableId }, courses, SchoolTimes.defaults(connection.timetableId))))
            assertFalse(backup.contains("SYNTHETIC_PASSWORD")); assertFalse(backup.contains("synthetic_session")); assertFalse(backup.contains("syncSource"))
        } }
    }
    @Test fun expiredSessionPerformsOneNormalLoginAndKeepsManualCourse() = runBlocking {
        SyntheticSchool().use { school -> fixture(school, false) { connection, _ ->
            assertEquals(ListenableWorker.Result.success(), worker(connection))
            assertEquals(1, school.loginCount.get())
            val dao = AppDatabase.get(context).dao()
            assertEquals("B202", dao.courses(connection.timetableId).single { it.name == "Synthetic Math" }.room)
            assertEquals(2, dao.courses(connection.timetableId).size)
            assertTrue(SyncVault(context).load(connection.sourceId)!!.cookies.isNotEmpty())
        } }
    }
    @Test fun captchaStopsBeforeLoginAndPreservesAllLocalData() = runBlocking {
        SyntheticSchool(captcha = true).use { school -> fixture(school, false) { connection, _ ->
            val dao = AppDatabase.get(context).dao(); val before = dao.courses(connection.timetableId)
            assertEquals(ListenableWorker.Result.success(), worker(connection))
            assertEquals(0, school.loginCount.get()); assertEquals(before, dao.courses(connection.timetableId))
            assertEquals("needs_login", dao.syncConnection(connection.timetableId)?.status)
        } }
    }
    @Test fun emptyOfficialResponseWaitsForApprovalAndThenRemovesOnlyOfficialCourses() = runBlocking {
        SyntheticSchool().use { school -> school.data = "{\"kbList\":[]}"
            fixture(school, true) { connection, _ ->
                val dao = AppDatabase.get(context).dao(); val before = dao.courses(connection.timetableId)
                assertEquals(ListenableWorker.Result.success(), worker(connection))
                val pending = dao.syncConnection(connection.timetableId)!!
                assertEquals("review", pending.status); assertEquals(before, dao.courses(connection.timetableId))
                assertTrue(SyncSnapshots.decode(pending.pendingSnapshot).isEmpty())
                SyncCoordinator.apply(context, pending, SyncSnapshots.decode(pending.pendingSnapshot), approve = true)
                assertEquals(listOf("自建自习"), dao.courses(connection.timetableId).map { it.name })
                assertEquals("ready", dao.syncConnection(connection.timetableId)?.status)
            }
        }
    }
    @Test fun incompleteOfficialResponseRetriesWithoutApplyingPartialChanges() = runBlocking {
        SyntheticSchool().use { school -> school.data = """{"kbList":[{"kcmc":"Synthetic Math","xm":"Synthetic Teacher","cdmc":"B202","xqj":"2","jcs":"3-4","zcd":"1-4周"},{"kcmc":"Unreadable"}]}"""
            fixture(school, true) { connection, _ ->
                val dao = AppDatabase.get(context).dao(); val before = dao.courses(connection.timetableId)
                assertEquals(ListenableWorker.Result.retry(), worker(connection))
                assertEquals(before, dao.courses(connection.timetableId)); assertEquals("error", dao.syncConnection(connection.timetableId)?.status)
            }
        }
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingLegacyCourseOrTimes() = runBlocking {
        val name = "sync-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE timetables (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, startDate TEXT NOT NULL, weekCount INTEGER NOT NULL, sectionCount INTEGER NOT NULL, showWeekend INTEGER NOT NULL, showOtherWeeks INTEGER NOT NULL, startDateConfirmed INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timetableId INTEGER NOT NULL, name TEXT NOT NULL, teacher TEXT NOT NULL, room TEXT NOT NULL, weekday INTEGER NOT NULL, startSection INTEGER NOT NULL, endSection INTEGER NOT NULL, weeks TEXT NOT NULL, color INTEGER NOT NULL, note TEXT NOT NULL, FOREIGN KEY(timetableId) REFERENCES timetables(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_courses_timetableId ON courses(timetableId)")
            old.execSQL("CREATE TABLE time_slots (timetableId INTEGER NOT NULL, section INTEGER NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, PRIMARY KEY(timetableId, section), FOREIGN KEY(timetableId) REFERENCES timetables(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_time_slots_timetableId ON time_slots(timetableId)")
            old.execSQL("INSERT INTO timetables VALUES(71,'原课表','2026-09-07',20,12,1,0,1)")
            old.execSQL("INSERT INTO courses VALUES(31,71,'原课程','原教师','A101',1,1,2,'1,2',5,'原备注')")
            old.execSQL("INSERT INTO time_slots VALUES(71,1,'08:00','08:45')"); old.version = 1
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val dao = migrated.dao(); val course = dao.courses(71).single()
            assertEquals(31L, course.id); assertEquals("原备注", course.note); assertEquals("", course.syncSource)
            assertEquals("08:00", dao.slots(71).single().start); assertTrue(dao.syncConnections().isEmpty())
            dao.saveSyncConnection(SyncConnection(71, UUID.randomUUID().toString(), "合成学校"))
            assertEquals(1, dao.syncConnections().size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
