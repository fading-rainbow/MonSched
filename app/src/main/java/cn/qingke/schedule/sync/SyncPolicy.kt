package cn.qingke.schedule.sync

import cn.qingke.schedule.data.*
import java.net.URI
import java.time.*

object SyncSafety {
    fun sameOrigin(a: String, b: String): Boolean = runCatching {
        val x = URI(a); val y = URI(b)
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80
        x.userInfo == null && y.userInfo == null && x.host != null && y.host != null &&
            x.scheme.lowercase() in setOf("http", "https") && x.scheme.equals(y.scheme, true) &&
            x.host.equals(y.host, true) && port(x) == port(y)
    }.getOrDefault(false)
    fun nextMidnight(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    fun due(lastAttempt: Long, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        lastAttempt == 0L || Instant.ofEpochMilli(lastAttempt).atZone(zone).toLocalDate() < now.atZone(zone).toLocalDate()
}

data class SyncDelta(val updates: List<Course>, val removed: List<Course>, val added: Int, val changed: Int, val review: Boolean) {
    val summary: String get() = if (added + changed + removed.size == 0) "官方课表没有变化"
        else "新增 $added、调整 $changed、移除 ${removed.size} 个官方时段"
}

object SyncReconciler {
    fun plan(local: List<Course>, official: List<Course>, source: String, timetableId: Long,
        adoptExactLegacy: Boolean = false): SyncDelta {
        require(source.isNotBlank())
        val remaining = local.filter { it.timetableId == timetableId && it.syncSource == source }.toMutableList()
        val legacy = if (adoptExactLegacy) local.filter { it.timetableId == timetableId && it.syncSource.isBlank() }.toMutableList() else mutableListOf()
        val remoteCourses = official.distinctBy { it.identity() }
        val reserved = remoteCourses.map { it.identity() }.toSet()
        val removedDuplicates = mutableListOf<Course>()
        val oldCount = remaining.distinctBy { it.identity() }.size
        var added = 0; var changed = 0
        val updates = remoteCourses.map { remote ->
            val exacts = remaining.filter { it.identity() == remote.identity() } + legacy.filter { it.identity() == remote.identity() }
            val exact = exacts.takeIf { it.isNotEmpty() }?.let(CourseDuplicates::merge)
            val old = exact ?: remaining.filter { it.name == remote.name && it.teacher == remote.teacher && it.identity() !in reserved }
                .maxByOrNull { c -> (if (c.weekday == remote.weekday) 8 else 0) +
                    (if (c.startSection == remote.startSection) 4 else 0) +
                    (if (c.room == remote.room) 2 else 0) + (if (c.weeks == remote.weeks) 1 else 0) }
            if (old == null) added++ else {
                if (exact != null) {
                    remaining.removeAll(exacts.toSet()); legacy.removeAll(exacts.toSet())
                    removedDuplicates += exacts.filter { it.id != old.id }
                } else { remaining.remove(old); changed++ }
            }
            remote.copy(id = old?.id ?: 0, timetableId = timetableId, syncSource = source,
                color = old?.color ?: remote.color, note = old?.note ?: remote.note)
        }
        // An empty response or a sudden drop needs a person to verify the term / school result.
        val review = updates.isEmpty() || oldCount >= 4 && updates.size * 2 < oldCount
        return SyncDelta(updates, remaining + removedDuplicates, added, changed, review)
    }
}
