package cn.qingke.schedule.data

/** Only full course identity is used: a shared time alone never means a duplicate. */
data class CourseIdentity(val name: String, val teacher: String, val room: String, val weekday: Int,
    val start: Int, val end: Int, val weeks: Set<Int>)

fun Course.identity() = CourseIdentity(name.trim(), teacher.trim(), room.trim(), weekday,
    startSection, endSection, weekSet())

data class DuplicatePlan(val groups: List<List<Course>>) {
    val removedCount: Int get() = groups.sumOf { it.size - 1 }
    val updates: List<Course> get() = groups.map(CourseDuplicates::merge)
    val removedIds: List<Long> get() = groups.flatMap { group ->
        val keep = CourseDuplicates.merge(group).id
        group.filter { it.id != keep }.map { it.id }
    }
}

object CourseDuplicates {
    fun plan(courses: List<Course>): DuplicatePlan = DuplicatePlan(courses
        .groupBy { it.timetableId to it.identity() }.values.filter { group ->
            group.size > 1 && group.map { it.syncSource }.filter { it.isNotBlank() }.distinct().size <= 1
        })

    /** Keep the original ID / colour and every distinct note, together with the existing binding. */
    fun merge(group: List<Course>): Course {
        require(group.isNotEmpty())
        require(group.map { it.syncSource }.filter { it.isNotBlank() }.distinct().size <= 1)
        val ordered = group.sortedBy { if (it.id == 0L) Long.MAX_VALUE else it.id }
        val notes = ordered.map { it.note }.filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        return ordered.first().copy(note = notes, syncSource = ordered.firstOrNull { it.syncSource.isNotBlank() }?.syncSource.orEmpty())
    }

    fun overlaps(a: Course, b: Course): Boolean = a.weekday == b.weekday &&
        a.startSection <= b.endSection && b.startSection <= a.endSection && a.weekSet().intersect(b.weekSet()).isNotEmpty()
}
