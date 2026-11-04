package com.traytimer.data.repo

import com.traytimer.core.logic.DayMath
import com.traytimer.data.db.AppDatabase
import com.traytimer.data.db.OutSession
import kotlinx.coroutines.flow.Flow

/**
 * Wear-time tracking. The atomic fact is an OUT session; everything else is derived
 * (FR-TMR-12: time math is timestamp-based, survives app kill / reboot).
 */
class TimerRepository(private val db: AppDatabase) {

    fun observeSessions(): Flow<List<OutSession>> = db.outSessionDao().observeAll()
    fun observeOpen(): Flow<OutSession?> = db.outSessionDao().observeOpen()

    suspend fun getSessions(): List<OutSession> = db.outSessionDao().getAll()
    suspend fun getOpen(): OutSession? = db.outSessionDao().findOpen()

    /** Take the trays out (start of an out session). */
    suspend fun startOut(fromBackground: Boolean = false): OutSession {
        val existing = db.outSessionDao().findOpen()
        if (existing != null) return existing
        val s = OutSession(startEpoch = System.currentTimeMillis(), endEpoch = null, fromBackground = fromBackground)
        val id = db.outSessionDao().insert(s)
        return s.copy(id = id)
    }

    /** Put the trays back in (close the open session). Returns the closed session, or null if none open. */
    suspend fun stopOut(fromBackground: Boolean = false): OutSession? {
        val open = db.outSessionDao().findOpen() ?: return null
        val closed = open.copy(endEpoch = System.currentTimeMillis(), fromBackground = open.fromBackground || fromBackground)
        db.outSessionDao().update(closed)
        return closed
    }

    suspend fun toggle(): Boolean {
        return if (db.outSessionDao().findOpen() == null) {
            startOut(); true
        } else {
            stopOut(); false
        }
    }

    suspend fun deleteSession(id: Long) = db.outSessionDao().deleteById(id)

    suspend fun updateSession(session: OutSession) {
        require(session.endEpoch == null || session.endEpoch >= session.startEpoch) { "end before start" }
        db.outSessionDao().update(session)
    }

    suspend fun getSession(id: Long): OutSession? = db.outSessionDao().findById(id)

    /** Adds a historical trays-out event (out time -> in time). */
    suspend fun addOutSession(startEpoch: Long, endEpoch: Long): Long {
        require(endEpoch > startEpoch) { "in time must be after out time" }
        return db.outSessionDao().insert(
            OutSession(startEpoch = startEpoch, endEpoch = endEpoch)
        )
    }

}
