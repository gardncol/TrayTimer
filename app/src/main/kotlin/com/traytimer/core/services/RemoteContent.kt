package com.traytimer.core.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class BrandTemplate(
    val name: String,
    val defaultAligners: Int?,
    val daysPerAligner: Int?
)

/**
 * Aligner brand templates fetched from https://app.traytimer.com/brands.json (FR-TRT-03)
 * with a bundled fallback.
 */
class BrandsRepository(private val context: Context) {

    fun observeBrands(): Flow<List<BrandTemplate>> = flow {
        val remote = fetchRemote()
        if (remote != null) emit(remote) else emit(bundled())
    }

    suspend fun getBrands(): List<BrandTemplate> = fetchRemote() ?: bundled()

    private suspend fun fetchRemote(): List<BrandTemplate>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URL("https://app.traytimer.com/brands.json")
            val text = url.openConnection().let { conn ->
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.getInputStream().bufferedReader().use { it.readText() }
            }
            parse(text)
        }.getOrNull()
    }

    private fun bundled(): List<BrandTemplate> {
        val text = context.assets.open("brands.json").bufferedReader().use { it.readText() }
        return parse(text)
    }

    private fun parse(text: String): List<BrandTemplate> {
        val arr = JSONArray(text)
        val out = ArrayList<BrandTemplate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                BrandTemplate(
                    name = o.optString("name"),
                    defaultAligners = if (o.isNull("aligners")) null else o.optInt("aligners"),
                    daysPerAligner = if (o.isNull("days")) null else o.optInt("days")
                )
            )
        }
        return out.filter { it.name.isNotBlank() }
    }
}

/**
 * Server-driven minimum version check against android-version.txt (FR-SET-17).
 */
class UpdateChecker {

    data class UpdateState(val available: Boolean, val required: Boolean, val url: String?)

    suspend fun check(currentVersionCode: Int): UpdateState = withContext(Dispatchers.IO) {
        val state = runCatching {
            val text = java.net.URL("https://app.traytimer.com/android-version.txt")
                .openConnection().let { conn ->
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.getInputStream().bufferedReader().use { it.readText() }
                }.trim()
            val minAvailable = text.lines().firstOrNull()?.toIntOrNull()
            val required = text.lines().getOrNull(1)?.toIntOrNull() ?: minAvailable
            when {
                (required ?: 0) > currentVersionCode -> UpdateState(true, true, PLAY_URL)
                (minAvailable ?: 0) > currentVersionCode -> UpdateState(true, false, PLAY_URL)
                else -> UpdateState(false, false, null)
            }
        }.getOrNull()
        state ?: UpdateState(false, false, null)
    }

    companion object {
        const val PLAY_URL = "https://play.google.com/store/apps/details?id=com.traytimer"
    }
}

/**
 * In-app FAQ (localized) — https://app.traytimer.com/faq.<locale>.html (FR-SET-16).
 */
object FaqUrls {
    fun forLocale(tag: String): String {
        val locale = if (tag.startsWith("zh")) {
            if (tag.endsWith("TW") || tag.endsWith("HK")) "zh-TW" else "zh-CN"
        } else tag.substringBefore('-').lowercase()
        return "https://app.traytimer.com/faq.$locale.html"
    }
}

object SocialUrls {
    const val INSTAGRAM = "https://www.instagram.com/traytimer.plus"
    const val FACEBOOK = "https://www.facebook.com/traytimer.plus"
    const val TIKTOK = "https://www.tiktok.com/@traytimer"
    const val YOUTUBE = "https://www.youtube.com/@traytimer"
    const val REDNOTE = "https://www.xiaohongshu.com"
}