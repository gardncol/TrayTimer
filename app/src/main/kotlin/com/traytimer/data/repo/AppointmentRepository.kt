package com.traytimer.data.repo

import com.traytimer.data.db.AppDatabase
import com.traytimer.data.db.Appointment
import com.traytimer.data.db.DayNote
import kotlinx.coroutines.flow.Flow

class AppointmentRepository(private val db: AppDatabase) {

    fun observeAppointments(): Flow<List<Appointment>> = db.appointmentDao().observeAll()
    suspend fun upcoming(now: Long): List<Appointment> = db.appointmentDao().findUpcoming(now)
    suspend fun getById(id: Long): Appointment? = db.appointmentDao().findById(id)

    suspend fun add(appointment: Appointment): Long = db.appointmentDao().insert(appointment)

    suspend fun update(appointment: Appointment) = db.appointmentDao().update(appointment)

    suspend fun delete(appointment: Appointment) = db.appointmentDao().delete(appointment)

    suspend fun markReminderFired(appointment: Appointment) =
        db.appointmentDao().update(appointment.copy(reminderFired = true))

    // ---- day notes ----

    fun observeNote(epochDay: Long): Flow<DayNote?> = db.dayNoteDao().observeForDay(epochDay)
    suspend fun noteForDay(epochDay: Long): DayNote? = db.dayNoteDao().findForDay(epochDay)

    suspend fun saveNote(epochDay: Long, text: String) {
        val existing = db.dayNoteDao().findForDay(epochDay)
        if (existing == null) {
            if (text.isNotBlank()) db.dayNoteDao().insert(DayNote(epochDay = epochDay, note = text))
        } else if (text.isBlank()) {
            db.dayNoteDao().delete(existing)
        } else {
            db.dayNoteDao().update(existing.copy(note = text))
        }
    }

    suspend fun deleteNote(note: DayNote) = db.dayNoteDao().delete(note)

}
