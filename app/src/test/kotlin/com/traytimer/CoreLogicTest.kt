package com.traytimer

import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.GoalColor
import com.traytimer.core.logic.PlanEngine
import com.traytimer.core.logic.ReminderCadence
import com.traytimer.core.logic.StatsCalc
import com.traytimer.data.db.OutSession
import com.traytimer.data.db.PlanTray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreLogicTest {

    private val utc = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 30)

    private fun at(d: LocalDate, hour: Int, minute: Int = 0): Long =
        d.atTime(hour, minute).atZone(utc).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long?): OutSession =
        OutSession(id = 0, startEpoch = start, endEpoch = end)

    // ---------------- DayMath ----------------

    @Test
    fun `day start is midnight by default`() {
        val start = DayMath.dayStart(day, 0, utc)
        assertEquals(at(day, 0), start)
    }

    @Test
    fun `day start honours custom day reset time`() {
        val start = DayMath.dayStart(day, 4 * 60, utc) // 4:00 AM
        assertEquals(at(day, 4), start)
    }

    @Test
    fun `day total equals 24h minus out sessions`() {
        val start = DayMath.dayStart(day, 0, utc)
        val end = start + DayMath.MILLIS_PER_DAY
        val sessions = listOf(session(at(day, 12), at(day, 13))) // 1 hour out
        val inMillis = DayMath.inMillis(sessions, start, 0, end)
        assertEquals(23L * 3_600_000L, inMillis)
    }

    @Test
    fun `sessions crossing the day boundary are split`() {
        val d1 = day
        val d2 = day.plusDays(1)
        val start1 = DayMath.dayStart(d1, 0, utc)
        val start2 = DayMath.dayStart(d2, 0, utc)
        // out from 23:30 to 00:30 (1h total)
        val sessions = listOf(session(at(d1, 23, 30), at(d2, 0, 30)))
        val outDay1 = DayMath.outMillis(sessions, start1, start1 + DayMath.MILLIS_PER_DAY)
        val outDay2 = DayMath.outMillis(sessions, start2, start2 + DayMath.MILLIS_PER_DAY)
        assertEquals(30L * 60_000L, outDay1)
        assertEquals(30L * 60_000L, outDay2)
    }

    @Test
    fun `reminder retriggers every 5 minutes for the first half hour then every 15`() {
        assertEquals(5L * 60_000L, ReminderCadence.nextDelayMillis(0))
        assertEquals(5L * 60_000L, ReminderCadence.nextDelayMillis(25L * 60_000L))
        assertEquals(5L * 60_000L, ReminderCadence.nextDelayMillis(29L * 60_000L + 59_000L))
        assertEquals(15L * 60_000L, ReminderCadence.nextDelayMillis(30L * 60_000L))
        assertEquals(15L * 60_000L, ReminderCadence.nextDelayMillis(5L * 3_600_000L))
    }

    @Test
    fun `overlapping out sessions are counted once`() {
        val start = DayMath.dayStart(day, 0, utc)
        val end = start + DayMath.MILLIS_PER_DAY
        val sessions = listOf(
            session(at(day, 12), at(day, 13)),
            session(at(day, 12, 30), at(day, 13, 30))
        )
        // union 12:00-13:30 = 90 minutes
        assertEquals(90L * 60_000L, DayMath.outMillis(sessions, start, end))
    }

    @Test
    fun `today is capped at now`() {
        val start = DayMath.dayStart(day, 0, utc)
        val now = at(day, 12)
        val sessions = listOf(session(at(day, 10), at(day, 11)))
        assertEquals(11L * 3_600_000L, DayMath.inMillis(sessions, start, 0, now))
    }

    @Test
    fun `open out session counts up to now`() {
        val start = DayMath.dayStart(day, 0, utc)
        val now = at(day, 15)
        val sessions = listOf(session(at(day, 14), null))
        assertEquals(14L * 3_600_000L, DayMath.inMillis(sessions, start, 0, now))
    }

    @Test
    fun `containing day respects late day reset`() {
        val epoch = at(day, 1, 30) // 01:30 falls on the previous stats day
        val containing = DayMath.containingDay(epoch, 4 * 60, utc)
        assertEquals(day.minusDays(1), containing)
    }

    @Test
    fun `goal colors follow thresholds`() {
        val goal = 22L * 3_600_000L
        val green = 22L * 3_600_000L
        val yellow = 19L * 3_600_000L
        assertEquals(GoalColor.GREEN, DayMath.goalColor(22L * 3_600_000L, goal, green, yellow))
        assertEquals(GoalColor.YELLOW, DayMath.goalColor(20L * 3_600_000L, goal, green, yellow))
        assertEquals(GoalColor.RED, DayMath.goalColor(18L * 3_600_000L, goal, green, yellow))
    }

    // ---------------- PlanEngine ----------------

    private fun plan(total: Int = 20, current: Int = 1, days: Int = 7): List<PlanTray> {
        val start = at(day, 0)
        return PlanEngine.buildPlan(total, current, days, start)
    }

    @Test
    fun `plan anchors the current tray to its start date and back-fills earlier trays`() {
        val trays = plan(total = 10, current = 3, days = 7)
        val sorted = PlanEngine.sorted(trays)
        assertEquals(10, sorted.size)
        assertTrue(sorted[0].completed)
        assertTrue(sorted[1].completed)
        assertTrue(!sorted[2].completed)
        // current tray (3) starts today; trays 1 and 2 lie in the past
        assertEquals(at(day, 0), PlanEngine.effectiveStart(sorted[2]))
        assertEquals(-14L * DayMath.MILLIS_PER_DAY, PlanEngine.effectiveStart(sorted[0]) - at(day, 0))
        assertEquals(2, PlanEngine.currentIndex(sorted, at(day, 0)))
        assertEquals(7, PlanEngine.daysLeftInTray(sorted, at(day, 0)))
    }

    @Test
    fun `current tray 32 of 36 leaves 35 days in treatment`() {
        val trays = plan(total = 36, current = 32, days = 7)
        val sorted = PlanEngine.sorted(trays)
        assertEquals(32, sorted[31].label.toInt())
        assertEquals(at(day, 0), PlanEngine.effectiveStart(sorted[31]))
        assertEquals(31, PlanEngine.currentIndex(sorted, at(day, 0)))
        assertEquals(31, sorted.count { it.completed })
        assertEquals(7, PlanEngine.daysLeftInTray(sorted, at(day, 0)))
        // current tray + 4 remaining trays = 5 * 7 days
        assertEquals(35, PlanEngine.daysLeftInTreatment(sorted, at(day, 0)))
    }

    @Test
    fun `changing a tray duration shifts subsequent trays`() {
        val trays = plan(total = 10, current = 1, days = 7)
        val before = PlanEngine.sorted(trays)[5].plannedStartEpoch
        val updated = PlanEngine.changeDuration(trays, orderIndex = 4, newDays = 10, applyUntil = null, autoAdjust = true)
        val after = PlanEngine.sorted(updated)[5].plannedStartEpoch
        assertEquals(3L * DayMath.MILLIS_PER_DAY, after - before)
    }

    @Test
    fun `range duration application only affects the range`() {
        val trays = plan(total = 10, current = 1, days = 7)
        val updated = PlanEngine.changeDuration(trays, orderIndex = 2, newDays = 10, applyUntil = 4, autoAdjust = true)
        val sorted = PlanEngine.sorted(updated)
        assertEquals(10, sorted[2].plannedDays)
        assertEquals(10, sorted[4].plannedDays)
        assertEquals(7, sorted[5].plannedDays)
    }

    @Test
    fun `early switch is classified correctly`() {
        val trays = plan(total = 10, current = 1, days = 7)
        val plannedStartTray3 = PlanEngine.sorted(trays)[2].plannedStartEpoch
        assertEquals(PlanEngine.SwitchKind.Early, PlanEngine.classifySwitch(trays, 2, plannedStartTray3 - 2 * DayMath.MILLIS_PER_DAY, utc))
        assertEquals(PlanEngine.SwitchKind.Late, PlanEngine.classifySwitch(trays, 2, plannedStartTray3 + 2 * DayMath.MILLIS_PER_DAY, utc))
        assertEquals(PlanEngine.SwitchKind.NearBoundary, PlanEngine.classifySwitch(trays, 2, plannedStartTray3 - 3_600_000L, utc))
    }

    @Test
    fun `same-day switch is on schedule even at a wall-clock time`() {
        val trays = plan(total = 3, current = 1, days = 7)
        val planned = PlanEngine.sorted(trays)[1].plannedStartEpoch
        assertEquals(
            PlanEngine.SwitchKind.OnSchedule,
            PlanEngine.classifySwitch(trays, 1, planned + 8 * 3_600_000L, utc)
        )
    }

    @Test
    fun `switch logged mid-day starts that tray's wear at the logged time`() {
        val trays = PlanEngine.buildPlan(4, 1, 7, at(day, 0))
        val switchAt = at(day.plusDays(7), 8) // 08:00 on the day tray 2 starts
        val updated = PlanEngine.applySwitch(trays, targetIndex = 1, switchEpoch = switchAt, autoAdjust = false)

        // The new tray is current for the whole switch day, not only from tomorrow.
        assertEquals(1, PlanEngine.currentIndex(updated, at(day.plusDays(7), 0)))

        // Wear is credited from 08:00, so 10:00 has 2h on the new tray...
        val now = at(day.plusDays(7), 10)
        val wearNew = StatsCalc.wearForTray(emptyList(), updated, trayOrderIndex = 1, resetMinutes = 0, now = now)
        assertEquals(2 * 3_600_000L, wearNew)

        // ...and the previous tray ends exactly at the switch instant.
        val wearPrev = StatsCalc.wearForTray(emptyList(), updated, trayOrderIndex = 0, resetMinutes = 0, now = now)
        assertEquals(7 * DayMath.MILLIS_PER_DAY + 8 * 3_600_000L, wearPrev)
    }

    @Test
    fun `automatic adjustment moves subsequent trays when switching early`() {
        val trays = plan(total = 10, current = 1, days = 7)
        val switchDay = at(day, 0) + 12 * DayMath.MILLIS_PER_DAY // tray 3 is planned for day 14
        val updated = PlanEngine.applySwitch(trays, targetIndex = 2, switchEpoch = switchDay, autoAdjust = true)
        val sorted = PlanEngine.sorted(updated)
        assertEquals(switchDay, PlanEngine.effectiveStart(sorted[2]))
        // tray 4 starts 7 days after the early switch
        assertEquals(switchDay + 7 * DayMath.MILLIS_PER_DAY, PlanEngine.effectiveStart(sorted[3]))
    }

    @Test
    fun `plan without auto-adjust keeps planned dates`() {
        val trays = plan(total = 10, current = 1, days = 7)
        val plannedStartTray4 = PlanEngine.sorted(trays)[3].plannedStartEpoch
        val switchDay = at(day, 0) + 12 * DayMath.MILLIS_PER_DAY
        val updated = PlanEngine.applySwitch(trays, targetIndex = 2, switchEpoch = switchDay, autoAdjust = false)
        assertEquals(plannedStartTray4, PlanEngine.sorted(updated)[3].plannedStartEpoch)
    }

    @Test
    fun `days left in treatment counts to the last tray`() {
        val trays = plan(total = 10, current = 1, days = 7)
        // 10 trays * 7 days = 70 days total
        assertEquals(63, PlanEngine.daysLeftInTreatment(trays, at(day, 0) + 7 * DayMath.MILLIS_PER_DAY))
    }

    // ---------------- StatsCalc ----------------

    @Test
    fun `averages and goal attainment count from sessions`() {
        val start = DayMath.dayStart(day.minusDays(6), 0, utc)
        val sessions = ArrayList<OutSession>()
        // 7 days, each with a 1h out session -> 23h worn, all green
        for (i in 0 until 7) {
            val d = day.minusDays((6 - i).toLong())
            sessions.add(session(at(d, 12), at(d, 13)))
        }
        val now = at(day, 0) + DayMath.MILLIS_PER_DAY // full final day
        val stats = StatsCalc.dailyStats(
            sessions, utc, 0,
            goalMillis = 22L * 3_600_000L,
            greenMillis = 22L * 3_600_000L,
            yellowMillis = 19L * 3_600_000L,
            days = 7,
            todayEpochDay = day.toEpochDay(),
            now = now
        )
        val avg = StatsCalc.average(stats)
        assertEquals(7, avg.daysCounted)
        assertEquals(23L * 3_600_000L, avg.averageMillis)
        assertEquals(7, avg.daysMetGoal)
    }

    @Test
    fun `current streak breaks on a missed day`() {
        fun stat(daysAgo: Long, color: GoalColor) = StatsCalc.DayStat(day.toEpochDay() - daysAgo, 0, color)
        val stats = listOf(
            stat(3, GoalColor.GREEN),
            stat(2, GoalColor.RED),
            stat(1, GoalColor.GREEN),
            stat(0, GoalColor.GREEN)
        )
        assertEquals(2, StatsCalc.currentStreak(stats))
    }

    @Test
    fun `per-tray wear is attributed to the tray window`() {
        val start = at(day, 0)
        val trays = PlanEngine.buildPlan(4, 1, 7, start)
        // 1h out inside tray 2's window (day 7 -> day 14); 2h out in tray 3's window
        val sessions = listOf(
            session(start + 7 * DayMath.MILLIS_PER_DAY + 2 * 3_600_000L, start + 7 * DayMath.MILLIS_PER_DAY + 3 * 3_600_000L),
            session(start + 14 * DayMath.MILLIS_PER_DAY + 3_600_000L, start + 14 * DayMath.MILLIS_PER_DAY + 3 * 3_600_000L)
        )
        val now = start + 21 * DayMath.MILLIS_PER_DAY
        val wearTray2 = StatsCalc.wearForTray(sessions, trays, trayOrderIndex = 1, resetMinutes = 0, now = now)
        val wearTray3 = StatsCalc.wearForTray(sessions, trays, trayOrderIndex = 2, resetMinutes = 0, now = now)
        assertEquals(7 * DayMath.MILLIS_PER_DAY - 3_600_000L, wearTray2)
        assertEquals(7 * DayMath.MILLIS_PER_DAY - 2 * 3_600_000L, wearTray3)
    }

    @Test
    fun `format helpers render hours and minutes`() {
        assertEquals("22h 00m", DayMath.formatHm(22L * 3_600_000L))
        assertEquals("1h 30m", DayMath.formatHm(90L * 60_000L))
        assertEquals("0h 05m", DayMath.formatHm(5L * 60_000L))
    }
}
