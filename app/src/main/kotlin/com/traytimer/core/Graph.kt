package com.traytimer.core

import android.content.Context
import com.traytimer.core.services.BrandsRepository
import com.traytimer.data.db.AppDatabase
import com.traytimer.data.prefs.SettingsStore
import com.traytimer.data.repo.AppointmentRepository
import com.traytimer.data.repo.PhotoRepository
import com.traytimer.data.repo.TimerRepository
import com.traytimer.data.repo.TreatmentRepository

/** Lightweight service locator; initialized once from TrayTimerApp. */
object Graph {

    lateinit var appContext: Context
        private set

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }
    val timerRepository: TimerRepository by lazy { TimerRepository(database) }
    val treatmentRepository: TreatmentRepository by lazy { TreatmentRepository(database) }
    val appointmentRepository: AppointmentRepository by lazy { AppointmentRepository(database) }
    val photoRepository: PhotoRepository by lazy { PhotoRepository(appContext, database) }
    val brandsRepository: BrandsRepository by lazy { BrandsRepository(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}