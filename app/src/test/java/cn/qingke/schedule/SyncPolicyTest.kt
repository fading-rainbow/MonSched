package cn.qingke.schedule

import cn.qingke.schedule.data.Course
import cn.qingke.schedule.sync.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SyncPolicyTest {
    private fun official(id: Long, name: String = "数学") = Course(id = id, timetableId = 9, name = name,
        teacher = "教师", room = "A101", weeks = "1,2,3,4", syncSource = "official", color = 3, note = "自己的笔记")
    @Test fun changedRoomAndDayKeepIdentityNotesColorAndManualCourses() {
        val old = official(11)
        val manual = old.copy(id = 12, syncSource = "", name = "自习")
        val remote = old.copy(id = 0, room = "B202", weekday = 4, color = 6, note = "")
        val delta = SyncReconciler.plan(listOf(old, manual), listOf(remote), "official", 9)
        assertEquals(1, delta.changed); assertEquals(0, delta.added); assertTrue(delta.removed.isEmpty())
        assertEquals(11L, delta.updates.single().id); assertEquals("自己的笔记", delta.updates.single().note)
        assertEquals(3, delta.updates.single().color); assertEquals(4, delta.updates.single().weekday)
        assertEquals("", manual.syncSource)
    }
    @Test fun cancelledOfficialCourseIsRemovedButOtherSourcesAreUntouched() {
        val old = listOf(official(1), official(2, "英语"), official(3, "其他学校").copy(syncSource = "other"), official(4, "自习").copy(syncSource = ""))
        val delta = SyncReconciler.plan(old, listOf(official(0)), "official", 9)
        assertEquals(listOf(2L), delta.removed.map { it.id }); assertFalse(delta.review)
    }
    @Test fun exactDuplicateRemoteIsNotInsertedTwice() {
        val delta = SyncReconciler.plan(listOf(official(4)), listOf(official(0), official(0)), "official", 9)
        assertEquals(1, delta.updates.size); assertEquals(0, delta.changed); assertEquals(0, delta.added)
    }
    @Test fun multipleMeetingsMatchExactlyBeforePairingMovedMeeting() {
        val a = official(1); val b = a.copy(id = 2, weekday = 3)
        val delta = SyncReconciler.plan(listOf(a, b), listOf(b.copy(id = 0), a.copy(id = 0, weekday = 4)), "official", 9)
        assertEquals(listOf(2L, 1L), delta.updates.map { it.id }); assertEquals(1, delta.changed)
    }
    @Test fun emptyAndLargeDropsRequireReview() {
        assertTrue(SyncReconciler.plan(listOf(official(1)), emptyList(), "official", 9).review)
        val old = (1L..8L).map { official(it, "课程$it") }
        assertTrue(SyncReconciler.plan(old, old.take(3), "official", 9).review)
        assertFalse(SyncReconciler.plan(old, old.take(7), "official", 9).review)
    }
    @Test fun newOfficialDoesNotConvertSameLookingManualCourse() {
        val manual = official(6).copy(syncSource = "")
        val delta = SyncReconciler.plan(listOf(manual), listOf(official(0)), "official", 9)
        assertEquals(1, delta.added); assertEquals(0L, delta.updates.single().id); assertTrue(delta.removed.isEmpty())
    }
    @Test fun originBoundCredentialsRejectDowngradeDifferentPortAndLookalikeHost() {
        assertTrue(SyncSafety.sameOrigin("https://JW.example.edu:443/login", "https://jw.example.edu/kb?term=3"))
        for (url in listOf("http://jw.example.edu/login", "https://jw.example.edu:8443/login", "https://jw.example.edu.evil.test/login",
            "https://user:password@jw.example.edu/login", "file:///login", "javascript:alert(1)"))
            assertFalse(SyncSafety.sameOrigin(url, "https://jw.example.edu/login"))
    }
    @Test fun midnightUsesPhoneZoneAndCrossesYear() {
        val now = Instant.parse("2026-12-31T15:59:50Z")
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), SyncSafety.nextMidnight(now, ZoneId.of("Asia/Shanghai")))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), SyncSafety.nextMidnight(now, ZoneId.of("UTC")))
    }
    @Test fun midnightRespectsDaylightSavingRatherThan24Hours() {
        val now = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant()
        assertEquals(23L, Duration.between(now, SyncSafety.nextMidnight(now, ZoneId.of("Europe/Berlin"))).toHours())
    }
    @Test fun dueChecksLocalDay() {
        val now = Instant.parse("2026-10-11T16:00:00Z"); val zone = ZoneId.of("Asia/Shanghai")
        assertTrue(SyncSafety.due(now.minusSeconds(1).toEpochMilli(), now, zone))
        assertFalse(SyncSafety.due(now.plusSeconds(1).toEpochMilli(), now, zone))
        assertTrue(SyncSafety.due(0, now, zone))
    }
}
