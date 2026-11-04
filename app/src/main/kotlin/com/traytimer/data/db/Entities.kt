package com.traytimer.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A period during which aligners were OUT (eating, brushing, drinking).
 * startEpoch <= endEpoch (both epoch millis, zone-independent facts).
 * endEpoch == null means the trays are currently out (open session).
 * All wear-time statistics are derived from these atomic facts.
 */
@Entity(tableName = "out_sessions")
data class OutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpoch: Long,
    val endEpoch: Long?,
    /** true when this session was created by a widget / tile / voice action */
    val fromBackground: Boolean = false
)

@Entity(tableName = "plan_trays")
data class PlanTray(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = initial series, 1..n = refinements, 100+n = retainers */
    val phaseIndex: Int,
    /** INITIAL / REFINEMENT / RETAINER */
    val phaseType: String,
    /** Human label, e.g. "12" or "3U" */
    val label: String,
    val orderIndex: Int,
    val plannedDays: Int,
    /** Epoch millis at local midnight of the planned start day */
    val plannedStartEpoch: Long,
    /** Epoch millis at local midnight of the actual start day, once a switch is logged */
    val actualStartEpoch: Long? = null,
    /** true once the patient moved past this tray */
    val completed: Boolean = false
)

@Entity(tableName = "appointments")
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** epoch millis of the appointment start */
    val startEpoch: Long,
    val remindDayBefore: Boolean = false,
    val remindHourBefore: Boolean = true,
    val remindHoursBefore: Int? = null,
    val remindMinutesBefore: Int? = null,
    val reminderFired: Boolean = false
)

/** Notes attached to a day (pain level, rubber bands, ...) — key is epochDay (LocalDate). */
@Entity(tableName = "day_notes")
data class DayNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val note: String
)

@Entity(tableName = "teethies")
data class Teethie(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** relative path under filesDir */
    val filePath: String,
    val takenAtEpoch: Long,
    /** tray label active at capture time */
    val trayLabel: String,
    /** one of PhotoAngle keys */
    val angle: String,
    val mirrored: Boolean = false
)