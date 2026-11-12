package com.traytimer.data.repo

import com.traytimer.core.logic.DayMath
import com.traytimer.core.logic.PlanEngine
import com.traytimer.data.db.AppDatabase
import com.traytimer.data.db.PlanTray
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

class TreatmentRepository(private val db: AppDatabase) {

    fun observeTrays(): Flow<List<PlanTray>> = db.planTrayDao().observeAll()
    suspend fun getTrays(): List<PlanTray> = db.planTrayDao().getAll()

    suspend fun hasPlan(): Boolean = db.planTrayDao().count() > 0

    /** Create the initial plan during onboarding (FR-ONB-04). */
    suspend fun createPlan(
        totalAligners: Int,
        currentAligner: Int,
        daysPerAligner: Int,
        startDate: Long,
        labelPrefix: String = "",
        switchDayOfWeek: Int? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ) {
        val trays = PlanEngine.buildPlan(
            totalAligners, currentAligner, daysPerAligner, startDate, labelPrefix,
            switchDayOfWeek, zone
        )
        db.planTrayDao().deleteAll()
        db.planTrayDao().insertAll(trays)
    }

    /**
     * Log a switch (FR-TRT-05/06). Returns the classification so the caller can
     * confirm early/late/backward switches and inform the patient.
     */
    suspend fun logSwitch(targetIndex: Int, switchDate: Long, autoAdjust: Boolean): PlanEngine.SwitchKind {
        val trays = db.planTrayDao().getAll()
        val kind = PlanEngine.classifySwitch(trays, targetIndex, switchDate)
        val updated = PlanEngine.applySwitch(trays, targetIndex, switchDate, autoAdjust)
        db.planTrayDao().deleteAll()
        db.planTrayDao().insertAll(updated)
        return kind
    }

    /** Change a tray duration with optional range application (FR-TRT-04). */
    suspend fun changeDuration(orderIndex: Int, newDays: Int, applyUntil: Int? = null, autoAdjust: Boolean = true) {
        val trays = db.planTrayDao().getAll()
        val updated = PlanEngine.changeDuration(trays, orderIndex, newDays, applyUntil, autoAdjust)
        db.planTrayDao().deleteAll()
        db.planTrayDao().insertAll(updated)
    }

    /** Append a refinement series (FR-TRT-01). */
    suspend fun addRefinement(count: Int, daysPerAligner: Int) {
        val trays = PlanEngine.sorted(db.planTrayDao().getAll())
        val lastPhase = trays.maxOfOrNull { it.phaseIndex } ?: 0
        val lastOrder = trays.maxOfOrNull { it.orderIndex } ?: -1
        val last = trays.lastOrNull() ?: return
        val nextStart = PlanEngine.effectiveEnd(last)
        val phase = lastPhase + 1
        val new = (1..count).map { i ->
            PlanTray(
                phaseIndex = phase,
                phaseType = PlanEngine.PHASE_REFINEMENT,
                label = "R$i",
                orderIndex = lastOrder + i,
                plannedDays = daysPerAligner,
                plannedStartEpoch = nextStart + (i - 1) * daysPerAligner * DayMath.MILLIS_PER_DAY
            )
        }
        db.planTrayDao().insertAll(new)
    }

    /** Append a retainer phase. */
    suspend fun addRetainer(count: Int, daysPerAligner: Int) {
        val trays = PlanEngine.sorted(db.planTrayDao().getAll())
        val lastOrder = trays.maxOfOrNull { it.orderIndex } ?: -1
        val last = trays.lastOrNull() ?: return
        val nextStart = PlanEngine.effectiveEnd(last)
        val new = (1..count).map { i ->
            PlanTray(
                phaseIndex = 100,
                phaseType = PlanEngine.PHASE_RETAINER,
                label = "T$i",
                orderIndex = lastOrder + i,
                plannedDays = daysPerAligner,
                plannedStartEpoch = nextStart + (i - 1) * daysPerAligner * DayMath.MILLIS_PER_DAY
            )
        }
        db.planTrayDao().insertAll(new)
    }

    suspend fun deleteTray(tray: PlanTray) = db.planTrayDao().deleteById(tray.id)

    suspend fun deletePlan() = db.planTrayDao().deleteAll()

    // ---- derived helpers used by reminders / header ----

    suspend fun currentTray(zone: ZoneId): PlanTray? =
        PlanEngine.currentTray(getTrays(), todayMidnight(zone))

    suspend fun currentIndex(zone: ZoneId): Int =
        PlanEngine.currentIndex(getTrays(), todayMidnight(zone))

    companion object {
        fun todayMidnight(zone: ZoneId): Long =
            java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}