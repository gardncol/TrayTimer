package com.traytimer.data.repo

import android.content.Context
import android.net.Uri
import com.traytimer.data.db.AppDatabase
import com.traytimer.data.db.Appointment
import com.traytimer.data.db.DayNote
import com.traytimer.data.db.OutSession
import com.traytimer.data.db.PlanTray
import com.traytimer.data.db.Teethie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local-only data portability: export a .zip with all treatment data and photos,
 * or import one (replacing everything on this device).
 *
 * Export layout:
 *   data.json
 *   photos/<file>.jpg
 */
class DataPortRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val photos: PhotoRepository
) {

    suspend fun exportToZip(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildJson()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("data.json"))
                    zip.write(json.toString().toByteArray())
                    zip.closeEntry()
                    photos.photosDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry("photos/${file.name}"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } ?: error("Could not open the destination file")
        }
    }

    suspend fun importFromZip(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "traytimer-import-${System.currentTimeMillis()}")
            val tempPhotos = File(tempDir, "photos").apply { mkdirs() }
            try {
                var json: JSONObject? = null
                // Keep the underlying stream open for the whole extraction; closing it
                // before reading from the ZipInputStream fails with "Stream Closed".
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        while (entry != null) {
                            when {
                                entry.name == "data.json" -> {
                                    json = JSONObject(zip.readBytes().decodeToString())
                                }
                                entry.name.startsWith("photos/") && !entry.isDirectory -> {
                                    File(tempPhotos, File(entry.name).name).outputStream()
                                        .use { zip.copyTo(it) }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("Could not open the backup file")
                val data = json ?: error("Backup is missing data.json")
                applyData(data, tempPhotos)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private suspend fun buildJson(): JSONObject {
        val sessions = db.outSessionDao().getAll()
        val trays = db.planTrayDao().getAll()
        val appointments = db.appointmentDao().getAll()
        val notes = db.dayNoteDao().getAll()
        val teethies = db.teethieDao().getAll()

        return JSONObject().apply {
            put("app", "TrayTimer")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("sessions", JSONArray().apply {
                sessions.forEach { s ->
                    put(JSONObject().apply {
                        put("start", s.startEpoch)
                        put("end", s.endEpoch ?: JSONObject.NULL)
                        put("background", s.fromBackground)
                    })
                }
            })
            put("trays", JSONArray().apply {
                trays.forEach { t ->
                    put(JSONObject().apply {
                        put("phaseIndex", t.phaseIndex)
                        put("phaseType", t.phaseType)
                        put("label", t.label)
                        put("order", t.orderIndex)
                        put("days", t.plannedDays)
                        put("plannedStart", t.plannedStartEpoch)
                        put("actualStart", t.actualStartEpoch ?: JSONObject.NULL)
                        put("completed", t.completed)
                    })
                }
            })
            put("appointments", JSONArray().apply {
                appointments.forEach { a ->
                    put(JSONObject().apply {
                        put("title", a.title)
                        put("start", a.startEpoch)
                        put("dayBefore", a.remindDayBefore)
                        put("hourBefore", a.remindHourBefore)
                        put("hoursBefore", a.remindHoursBefore ?: JSONObject.NULL)
                        put("minutesBefore", a.remindMinutesBefore ?: JSONObject.NULL)
                        put("fired", a.reminderFired)
                    })
                }
            })
            put("notes", JSONArray().apply {
                notes.forEach { n ->
                    put(JSONObject().apply {
                        put("day", n.epochDay)
                        put("note", n.note)
                    })
                }
            })
            put("photos", JSONArray().apply {
                teethies.forEach { p ->
                    put(JSONObject().apply {
                        put("file", p.filePath)
                        put("takenAt", p.takenAtEpoch)
                        put("tray", p.trayLabel)
                        put("angle", p.angle)
                        put("mirrored", p.mirrored)
                    })
                }
            })
        }
    }

    private suspend fun applyData(json: JSONObject, tempPhotos: File) {
        val sessions = mutableListOf<OutSession>()
        json.optJSONArray("sessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                sessions.add(
                    OutSession(
                        startEpoch = o.getLong("start"),
                        endEpoch = if (o.isNull("end")) null else o.getLong("end"),
                        fromBackground = o.optBoolean("background", false)
                    )
                )
            }
        }
        val trays = mutableListOf<PlanTray>()
        json.optJSONArray("trays")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                trays.add(
                    PlanTray(
                        phaseIndex = o.optInt("phaseIndex", 0),
                        phaseType = o.optString("phaseType", "INITIAL"),
                        label = o.optString("label"),
                        orderIndex = o.getInt("order"),
                        plannedDays = o.optInt("days", 7),
                        plannedStartEpoch = o.getLong("plannedStart"),
                        actualStartEpoch = if (o.isNull("actualStart")) null else o.getLong("actualStart"),
                        completed = o.optBoolean("completed", false)
                    )
                )
            }
        }
        val appointments = mutableListOf<Appointment>()
        json.optJSONArray("appointments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                appointments.add(
                    Appointment(
                        title = o.optString("title"),
                        startEpoch = o.getLong("start"),
                        remindDayBefore = o.optBoolean("dayBefore", false),
                        remindHourBefore = o.optBoolean("hourBefore", true),
                        remindHoursBefore = if (o.isNull("hoursBefore")) null else o.optInt("hoursBefore"),
                        remindMinutesBefore = if (o.isNull("minutesBefore")) null else o.optInt("minutesBefore"),
                        reminderFired = o.optBoolean("fired", false)
                    )
                )
            }
        }
        val notes = mutableListOf<DayNote>()
        json.optJSONArray("notes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                notes.add(DayNote(epochDay = o.getLong("day"), note = o.optString("note")))
            }
        }
        val teethies = mutableListOf<Teethie>()
        json.optJSONArray("photos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = File(tempPhotos, o.getString("file"))
                if (file.isFile) {
                    teethies.add(
                        Teethie(
                            filePath = file.name,
                            takenAtEpoch = o.getLong("takenAt"),
                            trayLabel = o.optString("tray"),
                            angle = o.optString("angle", "facefront"),
                            mirrored = o.optBoolean("mirrored", false)
                        )
                    )
                }
            }
        }

        // Replace local data only after a successful parse/extract.
        db.outSessionDao().deleteAll()
        db.planTrayDao().deleteAll()
        db.appointmentDao().deleteAll()
        db.dayNoteDao().deleteAll()
        db.teethieDao().deleteAll()

        photos.photosDir.deleteRecursively(); photos.photosDir.mkdirs()
        photos.thumbsDir.deleteRecursively(); photos.thumbsDir.mkdirs()
        tempPhotos.listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(File(photos.photosDir, f.name), overwrite = true)
        }

        db.outSessionDao().insertAll(sessions)
        db.planTrayDao().insertAll(trays)
        db.appointmentDao().insertAll(appointments)
        db.dayNoteDao().insertAll(notes)
        db.teethieDao().insertAll(teethies)
        photos.regenerateThumbnails()
    }
}
