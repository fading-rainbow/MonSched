package cn.qingke.schedule

import cn.qingke.schedule.data.*
import cn.qingke.schedule.sync.SyncReconciler
import org.junit.Assert.*
import org.junit.Test

class CourseDuplicatesTest {
    private fun old(id: Long = 11) = Course(id = id, timetableId = 9, name = "数学", teacher = "教师", room = "A101",
        weeks = "1,2,3,4", note = "旧课表笔记", color = 5)
    @Test fun firstBindingReusesLegacyIdNoteAndColorWithoutAddingCopy() {
        val legacy = old(); val manual = legacy.copy(id = 12, name = "自习", room = "图书馆")
        val delta = SyncReconciler.plan(listOf(legacy, manual), listOf(legacy.copy(id = 0, note = "", color = 0)), "new", 9, true)
        assertEquals(0, delta.added); assertTrue(delta.removed.isEmpty())
        assertEquals(11L, delta.updates.single().id); assertEquals(5, delta.updates.single().color)
        assertEquals("旧课表笔记", delta.updates.single().note); assertEquals("new", delta.updates.single().syncSource)
    }
    @Test fun repeatedBindingConsolidatesLegacyAndOfficialCopyKeepingNotesAndBinding() {
        val legacy = old(); val copy = legacy.copy(id = 13, syncSource = "old", note = "同步后笔记", color = 0)
        val delta = SyncReconciler.plan(listOf(legacy, copy), listOf(legacy.copy(id = 0)), "old", 9, true)
        assertEquals(11L, delta.updates.single().id); assertEquals(listOf(13L), delta.removed.map { it.id })
        assertEquals("旧课表笔记\n\n同步后笔记", delta.updates.single().note)
        assertEquals("old", delta.updates.single().syncSource); assertEquals(5, delta.updates.single().color)
    }
    @Test fun ordinaryDailySyncNeverTakesOverAnUnboundManualCourse() {
        val delta = SyncReconciler.plan(listOf(old()), listOf(old(0)), "source", 9)
        assertEquals(1, delta.added); assertEquals(0L, delta.updates.single().id); assertTrue(delta.removed.isEmpty())
    }
    @Test fun matchingExactRowsAreReservedBeforeMovedRowsArePaired() {
        val a = old().copy(syncSource = "source")
        val b = a.copy(id = 12, weekday = 3, note = "第二个时段")
        val moved = a.copy(id = 0, weekday = 4)
        val delta = SyncReconciler.plan(listOf(a, b), listOf(moved, b.copy(id = 0)), "source", 9)
        assertEquals(listOf(11L, 12L), delta.updates.map { it.id })
        assertEquals("第二个时段", delta.updates[1].note)
    }
    @Test fun cleanupMergesNotesKeepsEarliestIdAndOfficialSource() {
        val a = old(); val b = a.copy(id = 14, syncSource = "source", note = "第二份备注", color = 0)
        val plan = CourseDuplicates.plan(listOf(a, b, a.copy(id = 15, name = "英语")))
        assertEquals(1, plan.removedCount); assertEquals(listOf(14L), plan.removedIds)
        val kept = plan.updates.single()
        assertEquals(11L, kept.id); assertEquals("source", kept.syncSource); assertEquals(5, kept.color)
        assertEquals("旧课表笔记\n\n第二份备注", kept.note)
    }
    @Test fun equivalentWeekOrderAndWhitespaceAreDuplicateButDifferentWeekSetsAreNot() {
        val a = old(); val b = a.copy(id = 12, name = " 数学 ", weeks = "4,3,2,1,1")
        val c = a.copy(id = 13, weeks = "1,3")
        assertEquals(1, CourseDuplicates.plan(listOf(a, b, c)).removedCount)
    }
    @Test fun sameTimeWithDifferentCourseTeacherRoomOrWeeksStaysUntouched() {
        val a = old()
        val others = listOf(a.copy(id = 12, name = "英语"), a.copy(id = 13, teacher = "另一个教师"),
            a.copy(id = 14, room = "B202"), a.copy(id = 15, weeks = "5,6"))
        assertEquals(0, CourseDuplicates.plan(listOf(a)+others).removedCount)
        assertTrue(CourseDuplicates.overlaps(a, others[0])); assertFalse(CourseDuplicates.overlaps(a, others[3]))
    }
    @Test fun separateTimetablesAndSeparateOfficialSourcesNeverMerge() {
        val a = old().copy(syncSource = "source1")
        assertEquals(0, CourseDuplicates.plan(listOf(a, a.copy(id = 12, timetableId = 10), a.copy(id = 13, syncSource = "source2"))).removedCount)
        val delta = SyncReconciler.plan(listOf(a.copy(timetableId = 10)), listOf(old(0)), "source1", 9, true)
        assertEquals(1, delta.added); assertTrue(delta.removed.isEmpty())
    }
    @Test fun repeatedRemoteRowsAndLegacyDuplicatesBecomeOneBoundRecord() {
        val a = old(); val b = a.copy(id = 12, note = a.note)
        val remote = a.copy(id = 0)
        val delta = SyncReconciler.plan(listOf(a,b), listOf(remote,remote), "new", 9, true)
        assertEquals(1, delta.updates.size); assertEquals(listOf(12L), delta.removed.map { it.id })
        assertEquals(a.note, delta.updates.single().note); assertEquals(0, delta.added)
    }
}
