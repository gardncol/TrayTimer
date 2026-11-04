package com.traytimer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutSessionDao {
    @Insert
    suspend fun insert(session: OutSession): Long

    @Update
    suspend fun update(session: OutSession)

    @Delete
    suspend fun delete(session: OutSession)

    @Query("DELETE FROM out_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM out_sessions ORDER BY startEpoch ASC")
    fun observeAll(): Flow<List<OutSession>>

    @Query("SELECT * FROM out_sessions WHERE endEpoch IS NULL ORDER BY startEpoch DESC LIMIT 1")
    suspend fun findOpen(): OutSession?

    @Query("SELECT * FROM out_sessions WHERE endEpoch IS NULL ORDER BY startEpoch DESC LIMIT 1")
    fun observeOpen(): Flow<OutSession?>

    @Query("SELECT * FROM out_sessions WHERE startEpoch < :windowEnd ORDER BY startEpoch ASC")
    suspend fun findUpTo(windowEnd: Long): List<OutSession>

    @Query("SELECT * FROM out_sessions WHERE id = :id")
    suspend fun findById(id: Long): OutSession?

    @Query("SELECT * FROM out_sessions ORDER BY startEpoch ASC")
    suspend fun getAll(): List<OutSession>

    @Query("SELECT COUNT(*) FROM out_sessions")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(sessions: List<OutSession>)

    @Query("DELETE FROM out_sessions")
    suspend fun deleteAll()
}

@Dao
interface PlanTrayDao {
    @Insert
    suspend fun insertAll(trays: List<PlanTray>)

    @Insert
    suspend fun insert(tray: PlanTray): Long

    @Update
    suspend fun update(tray: PlanTray)

    @Update
    suspend fun updateAll(trays: List<PlanTray>)

    @Query("DELETE FROM plan_trays WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM plan_trays")
    suspend fun deleteAll()

    @Query("SELECT * FROM plan_trays ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<PlanTray>>

    @Query("SELECT * FROM plan_trays ORDER BY orderIndex ASC")
    suspend fun getAll(): List<PlanTray>

    @Query("SELECT COUNT(*) FROM plan_trays")
    suspend fun count(): Int
}

@Dao
interface AppointmentDao {
    @Insert
    suspend fun insert(appointment: Appointment): Long

    @Update
    suspend fun update(appointment: Appointment)

    @Delete
    suspend fun delete(appointment: Appointment)

    @Query("SELECT * FROM appointments ORDER BY startEpoch ASC")
    fun observeAll(): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE startEpoch > :now ORDER BY startEpoch ASC")
    suspend fun findUpcoming(now: Long): List<Appointment>

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun findById(id: Long): Appointment?

    @Query("SELECT * FROM appointments ORDER BY startEpoch ASC")
    suspend fun getAll(): List<Appointment>

    @Insert
    suspend fun insertAll(appointments: List<Appointment>)

    @Query("DELETE FROM appointments")
    suspend fun deleteAll()
}

@Dao
interface DayNoteDao {
    @Insert
    suspend fun insert(note: DayNote): Long

    @Update
    suspend fun update(note: DayNote)

    @Delete
    suspend fun delete(note: DayNote)

    @Query("SELECT * FROM day_notes WHERE epochDay = :epochDay")
    suspend fun findForDay(epochDay: Long): DayNote?

    @Query("SELECT * FROM day_notes WHERE epochDay = :epochDay")
    fun observeForDay(epochDay: Long): Flow<DayNote?>

    @Query("SELECT * FROM day_notes ORDER BY epochDay ASC")
    suspend fun getAll(): List<DayNote>

    @Insert
    suspend fun insertAll(notes: List<DayNote>)

    @Query("DELETE FROM day_notes")
    suspend fun deleteAll()
}

@Dao
interface TeethieDao {
    @Insert
    suspend fun insert(teethie: Teethie): Long

    @Update
    suspend fun update(teethie: Teethie)

    @Delete
    suspend fun delete(teethie: Teethie)

    @Query("SELECT * FROM teethies ORDER BY takenAtEpoch ASC")
    fun observeAll(): Flow<List<Teethie>>

    @Query("SELECT * FROM teethies ORDER BY takenAtEpoch DESC")
    suspend fun getAll(): List<Teethie>

    @Query("SELECT * FROM teethies WHERE id = :id")
    suspend fun findById(id: Long): Teethie?

    @Query("SELECT COUNT(*) FROM teethies")
    suspend fun count(): Int

    @Query("SELECT MAX(takenAtEpoch) FROM teethies")
    suspend fun latestTakenAt(): Long?

    @Insert
    suspend fun insertAll(teethies: List<Teethie>)

    @Query("DELETE FROM teethies")
    suspend fun deleteAll()
}