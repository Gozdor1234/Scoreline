package com.nate.scoreline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP + JSON helpers. Uses Android's built-in org.json, no extra libraries. */
object Net {
    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Scoreology/1.0 (personal Android app)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

// Null-tolerant accessors. ESPN omits keys freely, so every read must survive absence.
fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }
}

/** ESPN mixes "7" (string) and 7.0 (number); render either as a clean string. */
fun JSONObject.numText(key: String): String {
    if (isNull(key)) return ""
    val d = optDouble(key, Double.NaN)
    if (!d.isNaN()) return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    return optString(key, "")
}
