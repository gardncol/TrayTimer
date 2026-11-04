package com.traytimer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        OutSession::class,
        PlanTray::class,
        Appointment::class,
        DayNote::class,
        Teethie::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outSessionDao(): OutSessionDao
    abstract fun planTrayDao(): PlanTrayDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun dayNoteDao(): DayNoteDao
    abstract fun teethieDao(): TeethieDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "traytimer.db"
                ).build().also { instance = it }
            }
    }
}