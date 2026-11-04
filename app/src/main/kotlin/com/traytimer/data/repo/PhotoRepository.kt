package com.traytimer.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.traytimer.core.logic.DayMath
import com.traytimer.data.db.AppDatabase
import com.traytimer.data.db.Teethie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Progress photos ("Teethies") — capture, storage, thumbnails. */
class PhotoRepository(
    private val context: Context,
    private val db: AppDatabase
) {

    enum class Angle(val key: String) {
        FACE_FRONT("facefront"),
        FACE_LEFT("faceleft"),
        FACE_RIGHT("faceright"),
        FACE_SMILE("facesmile"),
        BITE_FRONT("bitefront"),
        BITE_LEFT("biteleft"),
        BITE_RIGHT("biteright"),
        ARCH_UPPER("archupper"),
        ARCH_LOWER("archlower");

        companion object {
            fun fromKey(k: String): Angle = entries.firstOrNull { it.key == k } ?: FACE_FRONT
        }
    }

    fun observeAll(): Flow<List<Teethie>> = db.teethieDao().observeAll()
    suspend fun getAll(): List<Teethie> = db.teethieDao().getAll()
    suspend fun count(): Int = db.teethieDao().count()
    suspend fun latestTakenAt(): Long? = db.teethieDao().latestTakenAt()

    val photosDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    val thumbsDir: File get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    suspend fun addPhoto(
        bytes: ByteArray,
        takenAt: Long,
        trayLabel: String,
        angle: Angle,
        mirrored: Boolean
    ): Teethie = withContext(Dispatchers.IO) {
        val name = "t_${UUID.randomUUID()}.jpg"
        val file = File(photosDir, name)
        file.writeBytes(bytes)
        createThumbnail(file, name)
        val t = Teethie(
            filePath = name,
            takenAtEpoch = takenAt,
            trayLabel = trayLabel,
            angle = angle.key,
            mirrored = mirrored
        )
        val id = db.teethieDao().insert(t)
        t.copy(id = id)
    }

    suspend fun addFromUri(uri: Uri, takenAt: Long, trayLabel: String, angle: Angle): Teethie? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                requireNotNull(bytes)
                addPhoto(bytes, takenAt, trayLabel, angle, false)
            }.getOrNull()
        }

    suspend fun delete(teethie: Teethie) = withContext(Dispatchers.IO) {
        db.teethieDao().delete(teethie)
        File(photosDir, teethie.filePath).delete()
        File(thumbsDir, teethie.filePath).delete()
    }

    suspend fun update(teethie: Teethie) = db.teethieDao().update(teethie)

    /** Flip the stored image horizontally (FR-PHO-04). */
    suspend fun flipImage(teethie: Teethie) = withContext(Dispatchers.IO) {
        val file = File(photosDir, teethie.filePath)
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext
        val matrix = android.graphics.Matrix().apply { setScale(-1f, 1f)
        }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        FileOutputStream(file).use { flipped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        flipped.recycle()
        createThumbnail(file, teethie.filePath)
        update(teethie.copy(mirrored = !teethie.mirrored))
        Unit
    }

    suspend fun loadBitmap(teethie: Teethie): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(photosDir, teethie.filePath)
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun fileFor(teethie: Teethie): File = File(photosDir, teethie.filePath)

    /** Save a copy to the device photo library (opt-in). */
    suspend fun saveToLibrary(teethie: Teethie): Boolean = withContext(Dispatchers.IO) {
        val file = File(photosDir, teethie.filePath)
        runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, teethie.filePath)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrayTimer")
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /** Regenerate all thumbnails (FR-PHO-11). */
    suspend fun regenerateThumbnails() = withContext(Dispatchers.IO) {
        photosDir.listFiles()?.forEach { f ->
            if (f.isFile) createThumbnail(f, f.name)
        }
    }

    private fun createThumbnail(source: File, name: String) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, opts)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = calcSample(opts.outWidth, opts.outHeight) }
        val bmp = BitmapFactory.decodeFile(source.absolutePath, opts2) ?: return
        val matrix = android.graphics.Matrix()
        ExifInterface(source.absolutePath).let { exif ->
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            matrix.postRotate(
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            )
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        FileOutputStream(File(thumbsDir, name)).use { rotated.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (rotated != bmp) rotated.recycle()
        bmp.recycle()
    }

    private fun calcSample(w: Int, h: Int): Int {
        var sample = 1
        var dim = maxOf(w, h)
        while (dim / sample > 512) sample *= 2
        return sample
    }

    fun thumbnailFile(teethie: Teethie): File = File(thumbsDir, teethie.filePath)

    companion object {
        fun formatDay(epoch: Long): String {
            val d = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            return d.toString()
        }

        fun formatTime(epoch: Long): String {
            val t = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault())
            return "%02d:%02d".format(t.hour, t.minute)
        }
    }
}