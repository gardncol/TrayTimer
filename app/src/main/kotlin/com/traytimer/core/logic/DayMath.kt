package com.traytimer.core.logic

import com.traytimer.data.db.OutSession
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, zone-aware day math. A "day" runs from [resetMinutes] on date D to the same
 * wall-clock time on D+1 (default midnight). Sessions crossing the boundary are split.
 * Day total In time = window length - sum of out time inside the window.
 */
object DayMath {

    const val MILLIS_PER_DAY = 86_400_000L

    fun zone(useDeviceZone: Boolean, chosen: String): ZoneId =
        if (useDeviceZone) ZoneId.systemDefault()
        else runCatching { ZoneId.of(chosen) }.getOrDefault(ZoneId.systemDefault())

    /** Epoch millis at the configured day-reset boundary of [date]. */
    fun dayStart(date: LocalDate, resetMinutes: Int, zone: ZoneId): Long {
        val time = LocalTime.of(resetMinutes / 60 % 24, resetMinutes % 60)
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun dateAt(epoch: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()

    /** The day window (as used for stats) containing [epoch]. */
    fun containingDay(epoch: Long, resetMinutes: Int, zone: ZoneId): LocalDate {
        val adjusted = if (resetMinutes > 0) epoch - (resetMinutes * 60_000L) else epoch
        return Instant.ofEpochMilli(adjusted).atZone(zone).toLocalDate()
    }

    /**
     * Sum of OUT milliseconds inside [start, end), splitting sessions on the boundaries.
     * Overlapping intervals are merged so the same minute can never be counted twice
     * (e.g. when a user logs an out/in event that overlaps an existing break).
     */
    fun outMillis(sessions: List<OutSession>, start: Long, end: Long, now: Long = System.currentTimeMillis()): Long {
        if (end <= start) return 0
        val intervals = ArrayList<LongArray>(sessions.size)
        for (s in sessions) {
            val sEnd = s.endEpoch ?: min(now, end)
            val clippedStart = max(s.startEpoch, start)
            val clippedEnd = min(sEnd, end)
            if (clippedEnd > clippedStart) intervals.add(longArrayOf(clippedStart, clippedEnd))
        }
        if (intervals.isEmpty()) return 0
        intervals.sortBy { it[0] }
        var total = 0L
        var curStart = intervals[0][0]
        var curEnd = intervals[0][1]
        for (i in 1 until intervals.size) {
            val (s, e) = intervals[i]
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                total += curEnd - curStart
                curStart = s
                curEnd = e
            }
        }
        total += curEnd - curStart
        return total
    }

    /**
     * In-time (worn) milliseconds for the day window starting at [windowStart].
     * Today is capped at "now"; full days are capped at the window length.
     */
    fun inMillis(
        sessions: List<OutSession>,
        windowStart: Long,
        resetMinutes: Int,
        now: Long = System.currentTimeMillis()
    ): Long {
        val windowEnd = windowStart + MILLIS_PER_DAY
        val spanEnd = min(now, windowEnd)
        val span = spanEnd - windowStart
        if (span <= 0) return 0
        val out = outMillis(sessions, windowStart, windowEnd, now)
        return max(0, span - out)
    }

    /** Today's in-time, convenience overload from an epoch-day + zone. */
    fun inMillisForDay(
        epochDay: Long,
        sessions: List<OutSession>,
        resetMinutes: Int,
        zone: ZoneId,
        now: Long = System.currentTimeMillis()
    ): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        val windowStart = dayStart(date, resetMinutes, zone)
        return inMillis(sessions, windowStart, resetMinutes, now)
    }

    fun goalColor(inMillis: Long, goalMillis: Long, greenMillis: Long, yellowMillis: Long): GoalColor {
        return when {
            inMillis >= greenMillis -> GoalColor.GREEN
            inMillis >= yellowMillis -> GoalColor.YELLOW
            else -> GoalColor.RED
        }
    }

    /** Format millis as "Xh YYm" (or with seconds when showSeconds). */
    fun formatHm(millis: Long, showSeconds: Boolean = false): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (showSeconds) "%dh %02dm %02ds".format(h, m, s) else "%dh %02dm".format(h, m)
    }

    fun formatMinutes(totalMinutes: Long): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return "%dh %02dm".format(h, m)
    }
}

enum class GoalColor { GREEN, YELLOW, RED }