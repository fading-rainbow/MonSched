package cn.qingke.schedule.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM timetables ORDER BY id") fun observeTimetables(): Flow<List<Timetable>>
    @Query("SELECT * FROM timetables ORDER BY id") suspend fun timetables(): List<Timetable>
    @Query("SELECT * FROM courses WHERE timetableId = :id ORDER BY weekday, startSection, id") fun observeCourses(id: Long): Flow<List<Course>>
    @Query("SELECT * FROM courses WHERE timetableId = :id ORDER BY weekday, startSection, id") suspend fun courses(id: Long): List<Course>
    @Query("SELECT * FROM time_slots WHERE timetableId = :id ORDER BY section") fun observeSlots(id: Long): Flow<List<TimeSlot>>
    @Query("SELECT * FROM time_slots WHERE timetableId = :id ORDER BY section") suspend fun slots(id: Long): List<TimeSlot>
    @Insert suspend fun insertTimetable(timetable: Timetable): Long
    @Update suspend fun updateTimetable(timetable: Timetable)
    @Delete suspend fun deleteTimetable(timetable: Timetable)
    @Upsert suspend fun saveCourse(course: Course)
    @Insert suspend fun insertCourses(courses: List<Course>)
    @Delete suspend fun deleteCourse(course: Course)
    @Query("DELETE FROM courses WHERE timetableId = :id") suspend fun clearCourses(id: Long)
    @Upsert suspend fun saveSlots(slots: List<TimeSlot>)
    @Query("SELECT * FROM sync_connections ORDER BY timetableId") fun observeSyncConnections(): Flow<List<SyncConnection>>
    @Query("SELECT * FROM sync_connections ORDER BY timetableId") suspend fun syncConnections(): List<SyncConnection>
    @Query("SELECT * FROM sync_connections WHERE timetableId = :id") suspend fun syncConnection(id: Long): SyncConnection?
    @Upsert suspend fun saveSyncConnection(connection: SyncConnection)
    @Query("DELETE FROM sync_connections WHERE timetableId = :id") suspend fun deleteSyncConnection(id: Long)
    @Query("DELETE FROM courses WHERE id IN (:ids)") suspend fun deleteCourseIds(ids: List<Long>)
    @Query("UPDATE courses SET syncSource = '' WHERE syncSource = :source") suspend fun detachSyncCourses(source: String)
}

@Database(entities = [Timetable::class, Course::class, TimeSlot::class, SyncConnection::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ScheduleDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN syncSource TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_connections (timetableId INTEGER NOT NULL, sourceId TEXT NOT NULL, schoolName TEXT NOT NULL, enabled INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, lastAttempt INTEGER NOT NULL, lastSuccess INTEGER NOT NULL, lastChanged INTEGER NOT NULL, pendingSnapshot TEXT NOT NULL, PRIMARY KEY(timetableId), FOREIGN KEY(timetableId) REFERENCES timetables(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "qingke.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
