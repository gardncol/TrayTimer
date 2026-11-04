package com.traytimer.core.services

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class BrandTemplate(
    val name: String,
    val defaultAligners: Int?,
    val daysPerAligner: Int?
)

/**
 * Aligner brand templates loaded from the bundled brands.json.
 *
 * TrayTimer is fully offline: there is no remote fetch, no update check and no
 * external content lookup. Everything the app needs ships in the APK.
 */
class BrandsRepository(private val context: Context) {

    suspend fun getBrands(): List<BrandTemplate> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open("brands.json").bufferedReader().use { it.readText() }
            parse(text)
        }.getOrDefault(emptyList())
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
