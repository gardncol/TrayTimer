package com.traytimer.core.logic

import com.traytimer.data.db.PlanTray
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Treatment plan engine: ordered phases (initial aligners -> refinements -> retainers),
 * per-tray durations, chained starts, switch logging with off-schedule detection and
 * optional automatic recalculation.
 */
object PlanEngine {

    const val PHASE_INITIAL = "INITIAL"
    const val PHASE_REFINEMENT = "REFINEMENT"
    const val PHASE_RETAINER = "RETAINER"

    /**
     * Builds a fresh plan. [startEpoch] is the local-midnight epoch on which the
     * patient started their CURRENT aligner — not tray 1. Completed trays are
     * back-filled before that date and remaining trays follow after it.
     */
    fun buildPlan(
        totalAligners: Int,
        currentAligner: Int,
        daysPerAligner: Int,
        startEpoch: Long,
        labelPrefix: String = "",
        /** 1=Monday .. 7=Sunday. Future trays are pinned to this weekday. */
        switchDayOfWeek: Int? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<PlanTray> {
        require(totalAligners > 0) { "totalAligners must be > 0" }
        require(currentAligner in 1..totalAligners) { "current out of range" }
        require(daysPerAligner > 0) { "daysPerAligner must be > 0" }
        val trays = ArrayList<PlanTray>(totalAligners)

        // Back-fill completed trays before the current one.
        for (i in 1 until currentAligner) {
            val plannedStart = startEpoch - (currentAligner - i).toLong() * daysPerAligner * DayMath.MILLIS_PER_DAY
            trays.add(
                PlanTray(
                    phaseIndex = 0,
                    phaseType = PHASE_INITIAL,
                    label = labelPrefix + i,
                    orderIndex = i - 1,
                    plannedDays = daysPerAligner,
                    plannedStartEpoch = plannedStart,
                    completed = true
                )
            )
        }

        // Current + upcoming trays. Each future switch can be pinned to a weekday so a
        // one-off switch day (e.g. Monday) does not move the rest of the plan off the
        // patient's normal change day (e.g. Tuesdays).
        var prevStart = startEpoch
        for (i in currentAligner..totalAligners) {
            val plannedStart = if (i == currentAligner) {
                startEpoch
            } else {
                val base = prevStart + daysPerAligner * DayMath.MILLIS_PER_DAY
                if (switchDayOfWeek == null) base else alignToWeekday(base, switchDayOfWeek, zone)
            }
            trays.add(
                PlanTray(
                    phaseIndex = 0,
                    phaseType = PHASE_INITIAL,
                    label = labelPrefix + i,
                    orderIndex = i - 1,
                    plannedDays = daysPerAligner,
                    plannedStartEpoch = plannedStart,
                    actualStartEpoch = if (i == currentAligner) startEpoch else null,
                    completed = false
                )
            )
            prevStart = plannedStart
        }
        return trays
    }

    /** First occurrence of [dayOfWeek] on or after [epoch]'s local date, at local midnight. */
    fun alignToWeekday(epoch: Long, dayOfWeek: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val target = DayOfWeek.of(dayOfWeek.coerceIn(1, 7))
        var date = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
        while (date.dayOfWeek != target) date = date.plusDays(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun sorted(trays: List<PlanTray>): List<PlanTray> = trays.sortedBy { it.orderIndex }

    /**
     * The effective start of a tray: the logged actual start if present, else the planned start.
     */
    fun effectiveStart(tray: PlanTray): Long = tray.actualStartEpoch ?: tray.plannedStartEpoch

    fun effectiveEnd(tray: PlanTray): Long = effectiveStart(tray) + tray.plannedDays * DayMath.MILLIS_PER_DAY

    /**
     * The moment a tray stops being active within the plan: the next tray's effective
     * start when one exists, otherwise start + plannedDays. This keeps weekday-aligned
     * plans accurate (the first gap can differ from [plannedDays]).
     */
    fun effectiveEndInPlan(trays: List<PlanTray>, index: Int): Long {
        val sorted = sorted(trays)
        val tray = sorted.getOrNull(index) ?: return 0L
        return sorted.getOrNull(index + 1)?.let { effectiveStart(it) } ?: effectiveEnd(tray)
    }

    /** Chained plan: a tray with no explicit plan starts when the previous one ends. */
    fun chainedPlannedStarts(trays: List<PlanTray>): List<PlanTray> {
        val sorted = sorted(trays)
        if (sorted.isEmpty()) return sorted
        val out = ArrayList<PlanTray>(sorted.size)
        var prevEnd = effectiveStart(sorted.first())
        for ((idx, t) in sorted.withIndex()) {
            out.add(
                if (idx == 0) t
                else t.copy(plannedStartEpoch = prevEnd)
            )
            prevEnd = effectiveStart(out.last()) + out.last().plannedDays * DayMath.MILLIS_PER_DAY
        }
        return out
    }

    /**
     * Index of the tray currently worn (last tray whose effective start falls on or
     * before today), or first. A switch logged at 08:00 today is the current tray for
     * the whole day, so the cutoff is the end of the day rather than midnight.
     */
    fun currentIndex(trays: List<PlanTray>, todayEpoch: Long): Int {
        val sorted = sorted(trays)
        if (sorted.isEmpty()) return -1
        val cutoff = todayEpoch + DayMath.MILLIS_PER_DAY
        var idx = 0
        for ((i, t) in sorted.withIndex()) {
            if (effectiveStart(t) < cutoff) idx = i
        }
        return idx
    }

    fun currentTray(trays: List<PlanTray>, todayEpoch: Long): PlanTray? {
        val i = currentIndex(trays, todayEpoch)
        return if (i < 0) null else sorted(trays)[i]
    }

    fun daysLeftInTray(trays: List<PlanTray>, todayEpoch: Long): Int {
        val idx = currentIndex(trays, todayEpoch)
        if (idx < 0) return 0
        val left = ((effectiveEndInPlan(trays, idx) - todayEpoch) / DayMath.MILLIS_PER_DAY).toInt()
        return left.coerceAtLeast(0)
    }

    fun daysLeftInTreatment(trays: List<PlanTray>, todayEpoch: Long): Int {
        val last = sorted(trays).lastOrNull() ?: return 0
        val left = ((effectiveEnd(last) - todayEpoch) / DayMath.MILLIS_PER_DAY).toInt()
        return left.coerceAtLeast(0)
    }

    sealed class SwitchKind {
        data object OnSchedule : SwitchKind()
        data object Early : SwitchKind()
        data object Late : SwitchKind()
        data object Backward : SwitchKind()
        data object NearBoundary : SwitchKind()
    }

    /** Detect how a switch to [targetIndex] on [day] relates to the plan. */
    fun classifySwitch(
        trays: List<PlanTray>,
        targetIndex: Int,
        switchEpoch: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): SwitchKind {
        val sorted = sorted(trays)
        val target = sorted.getOrNull(targetIndex) ?: return SwitchKind.OnSchedule
        val switchDayStart = Instant.ofEpochMilli(switchEpoch)
            .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val currentIdx = currentIndex(trays, switchDayStart)
        if (currentIdx in sorted.indices && currentIdx > targetIndex) return SwitchKind.Backward
        val planned = target.plannedStartEpoch
        // Planned starts are stored at local midnight, while logged switches carry the
        // real wall-clock time. Compare dates so changing tray at 08:00 on the planned
        // day is still "on schedule" instead of "late".
        val plannedDay = Instant.ofEpochMilli(planned).atZone(zone).toLocalDate().toEpochDay()
        val switchDay = Instant.ofEpochMilli(switchEpoch).atZone(zone).toLocalDate().toEpochDay()
        return when {
            switchDay > plannedDay -> SwitchKind.Late
            switchDay == plannedDay -> SwitchKind.OnSchedule
            switchDay == plannedDay - 1 && switchEpoch > planned - NEAR_BOUNDARY_MILLIS ->
                SwitchKind.NearBoundary
            else -> SwitchKind.Early
        }
    }

    private val NEAR_BOUNDARY_MILLIS = 12 * 3_600_000L

    /**
     * Log a switch to tray [targetIndex] effective [switchEpoch].
     * When [autoAdjust] is on, subsequent planned starts are recalculated so the
     * whole remaining plan shifts with the actual switch date; otherwise planned
     * dates stay fixed and only actual starts move.
     */
    fun applySwitch(
        trays: List<PlanTray>,
        targetIndex: Int,
        switchEpoch: Long,
        autoAdjust: Boolean
    ): List<PlanTray> {
        val sorted = sorted(trays).toMutableList()
        if (targetIndex !in sorted.indices) return sorted
        val switchDayStart = Instant.ofEpochMilli(switchEpoch)
            .atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val wasBackward = targetIndex < currentIndex(sorted, switchDayStart)
        for (i in sorted.indices) {
            val t = sorted[i]
            sorted[i] = when {
                i < targetIndex -> t.copy(completed = true, actualStartEpoch = t.actualStartEpoch ?: t.plannedStartEpoch)
                i == targetIndex -> t.copy(actualStartEpoch = switchEpoch, completed = false)
                // Later trays become "upcoming" again; otherwise a backward switch would
                // leave an already-started tray looking like the current one.
                else -> t.copy(completed = false, actualStartEpoch = null)
            }
        }
        // A backward switch must always re-chain later planned starts: otherwise their
        // old (already-past) dates would still make them look like the current tray.
        return if (autoAdjust || wasBackward) chainedPlannedStarts(sorted) else sorted
    }

    /**
     * Change the planned duration of one tray. [applyUntil] (inclusive orderIndex)
     * applies the same duration to a range ("apply to subsequent aligners"); null
     * applies to just this tray.
     */
    fun changeDuration(
        trays: List<PlanTray>,
        orderIndex: Int,
        newDays: Int,
        applyUntil: Int? = null,
        autoAdjust: Boolean = true
    ): List<PlanTray> {
        require(newDays > 0) { "days must be positive" }
        val sorted = sorted(trays).toMutableList()
        val from = orderIndex
        val to = minOf(applyUntil ?: orderIndex, sorted.size - 1)
        for (i in from..to) sorted[i] = sorted[i].copy(plannedDays = newDays)
        return if (autoAdjust) chainedPlannedStarts(sorted) else sorted
    }

    /** Total planned wear hours the plan expects for a tray (days * goal hours). */
    fun plannedWearMillis(tray: PlanTray, goalMillisPerDay: Long): Long =
        tray.plannedDays * goalMillisPerDay

    /** Group trays by phase for the phase list UI. */
    fun phases(trays: List<PlanTray>): List<List<PlanTray>> =
        sorted(trays).groupBy { it.phaseIndex }.map { it.value }

    fun phaseTitle(phaseIndex: Int, phaseType: String): String = when (phaseType) {
        PHASE_INITIAL -> "Initial aligners"
        PHASE_REFINEMENT -> if (phaseIndex <= 1) "Refinement" else "Refinement ${phaseIndex}"
        PHASE_RETAINER -> "Retainer"
        else -> "Phase $phaseIndex"
    }

    fun nextLabel(trays: List<PlanTray>): String {
        val maxNum = sorted(trays).lastOrNull()?.label?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0
        return (maxNum + 1).toString()
    }

    fun epochDayOf(epoch: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(0).let { DayMath.dateAt(epoch, zone).toEpochDay() }
}