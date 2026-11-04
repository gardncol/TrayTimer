package com.traytimer.core.logic

import com.traytimer.data.db.OutSession
import com.traytimer.data.db.PlanTray
import java.time.LocalDate

/**
 * Statistics derived from the same timestamped session data as the timer/calendar —
 * no reconciliation drift between tabs (FR-STS-06).
 */
object StatsCalc {

    data class DayStat(val epochDay: Long, val inMillis: Long, val goalColor: GoalColor)

    data class Averages(val days: Int, val averageMillis: Long, val daysMetGoal: Int, val daysCounted: Int)

    /**
     * Daily stats for the last [days] days (excluding today unless [includeToday]).
     * Days before [firstTrackedEpochDay] (i.e. before the user ever logged wear) are
     * not counted — an untracked past day is not "24h worn".
     */
    fun dailyStats(
        sessions: List<OutSession>,
        zone: java.time.ZoneId,
        resetMinutes: Int,
        goalMillis: Long,
        greenMillis: Long,
        yellowMillis: Long,
        days: Int,
        todayEpochDay: Long,
        now: Long = System.currentTimeMillis(),
        includeToday: Boolean = true,
        firstTrackedEpochDay: Long? = null
    ): List<DayStat> {
        val out = ArrayList<DayStat>(days)
        val todayIdx = if (includeToday) 0 else 1
        for (i in (days - 1) downTo todayIdx) {
            val epochDay = todayEpochDay - i
            if (firstTrackedEpochDay != null && epochDay < firstTrackedEpochDay) continue
            val day = LocalDate.ofEpochDay(epochDay)
            val start = DayMath.dayStart(day, resetMinutes, zone)
            val inMillis = DayMath.inMillis(sessions, start, resetMinutes, now)
            out.add(DayStat(epochDay, inMillis, DayMath.goalColor(inMillis, goalMillis, greenMillis, yellowMillis)))
        }
        return out
    }

    fun average(stats: List<DayStat>): Averages {
        if (stats.isEmpty()) return Averages(0, 0, 0, 0)
        val met = stats.count { it.goalColor == GoalColor.GREEN }
        return Averages(stats.size, stats.sumOf { it.inMillis } / stats.size, met, stats.size)
    }

    /** Consecutive days (ending today, or the most recent day) meeting the green goal. */
    fun currentStreak(stats: List<DayStat>): Int {
        var streak = 0
        for (stat in stats.reversed()) {
            if (stat.goalColor == GoalColor.GREEN) streak++ else break
        }
        return streak
    }

    /**
     * Cumulative wear for a tray: in-time between the tray's effective start and the
     * effective start of the next tray (or now for the current tray).
     */
    fun wearForTray(
        sessions: List<OutSession>,
        trays: List<PlanTray>,
        trayOrderIndex: Int,
        resetMinutes: Int,
        now: Long = System.currentTimeMillis()
    ): Long {
        val sorted = PlanEngine.sorted(trays)
        if (trayOrderIndex !in sorted.indices) return 0
        val start = PlanEngine.effectiveStart(sorted[trayOrderIndex])
        val end = sorted.getOrNull(trayOrderIndex + 1)?.let { PlanEngine.effectiveStart(it) } ?: now
        if (end <= start) return 0
        // full-day windows inside the span + partial handling at edges
        return DayMath.outMillis(sessions, start, end, now).let { out ->
            (minOf(end, now) - start - out).coerceAtLeast(0)
        }
    }
}