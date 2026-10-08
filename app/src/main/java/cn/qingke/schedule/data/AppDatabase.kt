package cn.qingke.schedule.data

import android.content.Context
import androidx.room.*
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
}

@Database(entities = [Timetable::class, Course::class, TimeSlot::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ScheduleDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "qingke.db")
                .build().also { instance = it }
        }
    }
}
