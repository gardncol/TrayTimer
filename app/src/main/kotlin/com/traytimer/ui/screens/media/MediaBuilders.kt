package com.traytimer.ui.screens.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import com.traytimer.core.Graph
import com.traytimer.data.db.Teethie
import java.io.File
import java.io.FileOutputStream

/** Side-by-side compare rendering + share (FR-PHO-06/09). */
object ImageShare {

    fun renderCompare(context: Context, a: Teethie, b: Teethie): File? {
        val bmpA = load(a) ?: return null
        val bmpB = load(b) ?: return null
        val h = 720
        val wA = (bmpA.width * h.toFloat() / bmpA.height).toInt().coerceAtLeast(1)
        val wB = (bmpB.width * h.toFloat() / bmpB.height).toInt().coerceAtLeast(1)
        val scaledA = Bitmap.createScaledBitmap(bmpA, wA, h, true)
        val scaledB = Bitmap.createScaledBitmap(bmpB, wB, h, true)
        val gap = 8
        val out = Bitmap.createBitmap(wA + wB + gap, h + 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaledA, 0f, 60f, null)
        canvas.drawBitmap(scaledB, (wA + gap).toFloat(), 60f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 34f
            isFakeBoldText = true
        }
        canvas.drawText("Before · ${fmt(b.takenAtEpoch)}", 8f, 44f, paint)
        canvas.drawText("After · ${fmt(a.takenAtEpoch)}", (wA + gap + 8).toFloat(), 44f, paint)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "compare_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }

    private fun fmt(epoch: Long): String {
        val d = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return d.toString()
    }

    fun load(teethie: Teethie): Bitmap? =
        BitmapFactory.decodeFile(Graph.photoRepository.fileFor(teethie).absolutePath)

    fun shareFile(context: Context, file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
}

/** Collage builder (FR-PHO-07): grid of up to 6 photos, shareable. */
object CollageBuilder {

    fun build(context: Context, photos: List<Teethie>): File? {
        if (photos.size < 2) return null
        val cell = 480
        val cols = if (photos.size <= 2) 2 else (if (photos.size <= 4) 2 else 3)
        val rows = (photos.size + cols - 1) / cols
        val gap = 12
        val w = cols * cell + (cols + 1) * gap
        val h = rows * cell + (rows + 1) * gap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        photos.take(6).forEachIndexed { i, photo ->
            val bmp = ImageShare.load(photo) ?: return@forEachIndexed
            val scaled = Bitmap.createScaledBitmap(bmp, cell, cell, true)
            val col = i % cols
            val row = i / cols
            val x = gap + col * (cell + gap)
            val y = gap + row * (cell + gap)
            canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
        }
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "collage_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }
}

/**
 * Progress video (FR-PHO-08): before/after slideshow rendered with MediaCodec +
 * MediaMuxer (the shipped product uses an FFmpeg pipeline; this is the platform
 * equivalent with no external binaries). Frames are drawn into the codec input
 * surface through the standard EGL/GLES path. [secondsPerPhoto] is playback speed.
 */
object ProgressVideoBuilder {

    private const val FRAME_RATE = 30
    private const val TIMEOUT_US = 10_000L

    fun build(
        context: Context,
        photos: List<Teethie>,
        secondsPerPhoto: Int,
        width: Int = 720,
        height: Int = 720
    ): File? {
        if (photos.size < 2) return null
        val seconds = secondsPerPhoto.coerceIn(1, 5)
        val framesPerPhoto = seconds * FRAME_RATE
        val totalFrames = photos.size * framesPerPhoto

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val outFile = File(dir, "progress_${System.currentTimeMillis()}.mp4")

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: InputSurface? = null
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType("video/avc")
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(codec.createInputSurface())
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackHolder = intArrayOf(-1)
            val bufferInfo = MediaCodec.BufferInfo()

            val bitmaps = photos.mapNotNull { p ->
                ImageShare.load(p)?.let { bmp ->
                    val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                    if (scaled != bmp) bmp.recycle()
                    scaled
                }
            }
            if (bitmaps.size < 2) {
                bitmaps.forEach { it.recycle() }
                return null
            }

            val frameDurationNanos = seconds * 1_000_000_000L / framesPerPhoto
            for (frame in 0 until totalFrames) {
                val idx = (frame / framesPerPhoto).coerceAtMost(bitmaps.size - 1)
                inputSurface.drawFrame(bitmaps[idx], frame * frameDurationNanos)
                drainEncoder(codec, muxer, trackHolder, bufferInfo, false)
            }
            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, trackHolder, bufferInfo, true)

            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            if (trackHolder[0] >= 0) muxer.stop()
            return outFile
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            return null
        } finally {
            runCatching { inputSurface?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        trackHolder: IntArray,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean
    ) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(trackHolder[0] < 0) { "output format changed twice" }
                    trackHolder[0] = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outIndex >= 0 -> {
                    val track = trackHolder[0]
                    val encoded = codec.getOutputBuffer(outIndex)
                    if (encoded != null && bufferInfo.size > 0 && track >= 0) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /**
     * Minimal EGL/GLES bridge onto a MediaCodec input surface. (Surface.lockCanvas
     * is not usable for GPU-produced codec surfaces, so we render with GL.)
     */
    private class InputSurface(private val surface: android.view.Surface) {
        private var eglDisplay: android.opengl.EGLDisplay = android.opengl.EGL14.EGL_NO_DISPLAY
        private var eglContext: android.opengl.EGLContext = android.opengl.EGL14.EGL_NO_CONTEXT
        private var eglSurface: android.opengl.EGLSurface = android.opengl.EGL14.EGL_NO_SURFACE
        private var texId = 0
        private var vertexBuffer: java.nio.FloatBuffer = java.nio.ByteBuffer.allocateDirect(0)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            val attrs = intArrayOf(
                android.opengl.EGL14.EGL_RED_SIZE, 8,
                android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                android.opengl.EGL14.EGL_NONE
            )
            eglDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != android.opengl.EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(android.opengl.EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val num = IntArray(1)
            check(
                android.opengl.EGL14.eglChooseConfig(
                    eglDisplay, attrs, 0, configs, 0, configs.size, num, 0
                ) && num[0] > 0
            ) { "no suitable EGLConfig" }
            eglContext = android.opengl.EGL14.eglCreateContext(
                eglDisplay, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT,
                intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0
            )
            eglSurface = android.opengl.EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], surface, intArrayOf(android.opengl.EGL14.EGL_NONE), 0
            )
            check(android.opengl.EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }
            initGl()
        }

        private fun initGl() {
            val vertex =
                "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() { vTexCoord = aTexCoord; gl_Position = aPosition; }"
            val fragment =
                "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"

            fun compile(type: Int, src: String): Int {
                val s = android.opengl.GLES20.glCreateShader(type)
                android.opengl.GLES20.glShaderSource(s, src)
                android.opengl.GLES20.glCompileShader(s)
                return s
            }
            val program = android.opengl.GLES20.glCreateProgram()
            android.opengl.GLES20.glAttachShader(program, compile(android.opengl.GLES20.GL_VERTEX_SHADER, vertex))
            android.opengl.GLES20.glAttachShader(program, compile(android.opengl.GLES20.GL_FRAGMENT_SHADER, fragment))
            android.opengl.GLES20.glLinkProgram(program)
            android.opengl.GLES20.glUseProgram(program)

            val aPositionLoc = android.opengl.GLES20.glGetAttribLocation(program, "aPosition")
            val aTexCoordLoc = android.opengl.GLES20.glGetAttribLocation(program, "aTexCoord")

            // interleaved x,y,u,v for a full-screen triangle strip
            val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                -1f, 1f, 0f, 0f,
                1f, -1f, 1f, 1f,
                1f, 1f, 1f, 0f
            )
            vertexBuffer = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(quad); position(0) }

            android.opengl.GLES20.glEnableVertexAttribArray(aPositionLoc)
            android.opengl.GLES20.glVertexAttribPointer(
                aPositionLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, vertexBuffer.position(0)
            )
            android.opengl.GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            android.opengl.GLES20.glVertexAttribPointer(
                aTexCoordLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, vertexBuffer.position(2)
            )

            val tex = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,
                android.opengl.GLES20.GL_LINEAR
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,
                android.opengl.GLES20.GL_LINEAR
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_WRAP_S,
                android.opengl.GLES20.GL_CLAMP_TO_EDGE
            )
            android.opengl.GLES20.glTexParameteri(
                android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_WRAP_T,
                android.opengl.GLES20.GL_CLAMP_TO_EDGE
            )
        }

        fun drawFrame(bitmap: Bitmap, timeNanos: Long) {
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
            android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
            android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timeNanos)
            check(android.opengl.EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
        }

        fun release() {
            runCatching {
                android.opengl.EGL14.eglMakeCurrent(
                    eglDisplay,
                    android.opengl.EGL14.EGL_NO_SURFACE,
                    android.opengl.EGL14.EGL_NO_SURFACE,
                    android.opengl.EGL14.EGL_NO_CONTEXT
                )
                android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface)
                android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext)
                android.opengl.EGL14.eglTerminate(eglDisplay)
            }
            surface.release()
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }
}