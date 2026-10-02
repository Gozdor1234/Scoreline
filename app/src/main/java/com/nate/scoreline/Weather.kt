package com.nate.scoreline

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, STORM, SNOW, WIND, INDOOR }

/** Game-time forecast. [tempF] is null when only the conditions are known. */
data class GameWeather(val tempF: Int?, val text: String, val sky: Sky, val night: Boolean = false)

/** Where a game is played, for the forecast fallback. */
data class GameVenue(val city: String, val state: String, val indoor: Boolean)

object WeatherData {
    /**
     * ESPN's game weather: {"displayValue":"Partly Cloudy","temperature":72,"highTemperature":72,"conditionId":"35"}.
     * conditionId is an AccuWeather icon number (33 and up are the night versions).
     */
    fun parseEspn(w: JSONObject?): GameWeather? {
        if (w == null) return null
        val raw = w.str("displayValue").trim()
        // Some games (often live ones) send the AccuWeather condition number as the text, like "7".
        val id = w.str("conditionId").trim().toIntOrNull() ?: raw.toIntOrNull() ?: 0
        val text = if (raw.any { it.isLetter() }) raw else accuText(id)
        val temp = listOf("temperature", "highTemperature").firstNotNullOfOrNull { k ->
            if (w.has(k) && !w.isNull(k)) w.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.roundToInt() else null
        }
        if (text.isBlank() && temp == null && id == 0) return null
        val sky = if (id != 0) accuSky(id) else skyFromText(text)
        return GameWeather(temp, text.ifBlank { label(sky) }, sky, night = id in 33..44)
    }

    /** AccuWeather condition names by icon number; "" when unknown. */
    fun accuText(id: Int): String = when (id) {
        1, 30 -> "Sunny"
        2 -> "Mostly Sunny"
        3 -> "Partly Sunny"
        4, 36 -> "Intermittent Clouds"
        5 -> "Hazy Sunshine"
        6, 38 -> "Mostly Cloudy"
        7 -> "Cloudy"
        8 -> "Dreary"
        11 -> "Fog"
        12 -> "Showers"
        13, 40 -> "Mostly Cloudy w/ Showers"
        14 -> "Partly Sunny w/ Showers"
        15 -> "Thunderstorms"
        16, 42 -> "Mostly Cloudy w/ T-Storms"
        17 -> "Partly Sunny w/ T-Storms"
        18 -> "Rain"
        19 -> "Flurries"
        20, 43 -> "Mostly Cloudy w/ Flurries"
        21 -> "Partly Sunny w/ Flurries"
        22 -> "Snow"
        23, 44 -> "Mostly Cloudy w/ Snow"
        24 -> "Ice"
        25 -> "Sleet"
        26 -> "Freezing Rain"
        29 -> "Rain and Snow"
        31 -> "Cold"
        32 -> "Windy"
        33 -> "Clear"
        34 -> "Mostly Clear"
        35 -> "Partly Cloudy"
        37 -> "Hazy Moonlight"
        39 -> "Partly Cloudy w/ Showers"
        41 -> "Partly Cloudy w/ T-Storms"
        else -> ""
    }

    fun accuSky(id: Int): Sky = when (id) {
        1, 2, 5, 30, 31, 33, 34, 37 -> Sky.CLEAR
        3, 4, 35, 36 -> Sky.PARTLY
        6, 7, 8, 38 -> Sky.CLOUDY
        11 -> Sky.FOG
        12, 13, 14, 18, 26, 39, 40 -> Sky.RAIN
        15, 16, 17, 41, 42 -> Sky.STORM
        19, 20, 21, 22, 23, 24, 25, 29, 43, 44 -> Sky.SNOW
        32 -> Sky.WIND
        else -> Sky.CLOUDY
    }

    fun skyFromText(t: String): Sky {
        val s = t.lowercase()
        return when {
            "storm" in s || "thunder" in s -> Sky.STORM
            "snow" in s || "flurr" in s || "sleet" in s || "ice" in s -> Sky.SNOW
            "rain" in s || "shower" in s || "drizzle" in s -> Sky.RAIN
            "fog" in s || "haz" in s && "sun" !in s -> Sky.FOG
            "wind" in s -> Sky.WIND
            "partly" in s || "intermittent" in s || "mostly sunny" in s || "mostly clear" in s -> Sky.PARTLY
            "cloud" in s || "overcast" in s || "dreary" in s -> Sky.CLOUDY
            else -> Sky.CLEAR
        }
    }

    /** Open-Meteo weather codes (WMO). */
    fun wmoSky(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    fun wmoText(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 77 -> "Snow"
        75 -> "Heavy Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow Showers"
        95, 96, 99 -> "Thunderstorms"
        else -> "Cloudy"
    }

    fun label(sky: Sky) = when (sky) {
        Sky.CLEAR -> "Clear"
        Sky.PARTLY -> "Partly Cloudy"
        Sky.CLOUDY -> "Cloudy"
        Sky.FOG -> "Fog"
        Sky.RAIN -> "Rain"
        Sky.STORM -> "Storms"
        Sky.SNOW -> "Snow"
        Sky.WIND -> "Windy"
        Sky.INDOOR -> "Indoors"
    }

    private val STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois",
        "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
        "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
        "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon",
        "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia",
        "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )

    /** Best geocoding hit for a venue city: same state first, then the biggest town. */
    fun pickPlace(root: JSONObject, state: String): Pair<Double, Double>? {
        val hits = root.arr("results").objects()
        if (hits.isEmpty()) return null
        val want = (STATES[state.uppercase()] ?: state).lowercase()
        val best = hits.firstOrNull { want.isNotEmpty() && it.str("admin1").lowercase() == want }
            ?: hits.maxByOrNull { it.optLong("population", 0) }!!
        return best.optDouble("latitude") to best.optDouble("longitude")
    }

    /** The forecast hour closest to kickoff from an Open-Meteo hourly block (times in UTC). */
    fun pickHour(root: JSONObject, kickoffUtc: Instant): GameWeather? {
        val h = root.obj("hourly") ?: return null
        val times = h.arr("time").strings()
        val temps = h.arr("temperature_2m")
        val codes = h.arr("weather_code")
        val day = h.arr("is_day")
        if (times.isEmpty() || temps == null || codes == null) return null
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val target = kickoffUtc.epochSecond
        val i = times.indices.minByOrNull { idx ->
            runCatching { kotlin.math.abs(java.time.LocalDateTime.parse(times[idx], fmt).toEpochSecond(ZoneOffset.UTC) - target) }
                .getOrDefault(Long.MAX_VALUE)
        } ?: return null
        val t = java.time.LocalDateTime.parse(times[i], fmt).toEpochSecond(ZoneOffset.UTC)
        if (kotlin.math.abs(t - target) > 2 * 3600) return null
        if (temps.isNull(i) || codes.isNull(i)) return null
        val code = codes.optInt(i)
        return GameWeather(temps.optDouble(i).roundToInt(), wmoText(code), wmoSky(code), night = day != null && day.optInt(i, 1) == 0)
    }

    private val geoLock = Mutex()
    private val geoMem = HashMap<String, Pair<Double, Double>?>()
    private val fcLock = Mutex()
    private val forecasts = HashMap<String, Pair<Long, JSONObject>>()

    private suspend fun place(ctx: Context, v: GameVenue): Pair<Double, Double>? = geoLock.withLock {
        val key = "${v.city}|${v.state}".lowercase()
        if (geoMem.containsKey(key)) return@withLock geoMem[key]
        val prefs = ctx.getSharedPreferences("venue_geo", Context.MODE_PRIVATE)
        prefs.getString(key, null)?.split(',')?.let { p ->
            val ll = p.getOrNull(0)?.toDoubleOrNull()?.let { a -> p.getOrNull(1)?.toDoubleOrNull()?.let { b -> a to b } }
            if (ll != null) { geoMem[key] = ll; return@withLock ll }
        }
        val ll = runCatching {
            pickPlace(
                Net.getJson("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(v.city, "UTF-8")}&count=10&language=en&format=json&countryCode=US"),
                v.state,
            )
        }.getOrNull()
        geoMem[key] = ll
        if (ll != null) prefs.edit().putString(key, "${ll.first},${ll.second}").apply()
        ll
    }

    /**
     * Forecast for a game ESPN hasn't posted weather for yet, from Open-Meteo by the venue's city.
     * Open-Meteo forecasts 16 days out; each location is fetched at most once an hour.
     */
    suspend fun forecast(ctx: Context, v: GameVenue, kickoffIso: String): GameWeather? {
        if (v.indoor) return GameWeather(null, "Indoors", Sky.INDOOR)
        if (v.city.isBlank()) return null
        val kick = runCatching { OffsetDateTime.parse(kickoffIso).toInstant() }.getOrNull() ?: return null
        val now = Instant.now()
        if (kick.isAfter(now.plusSeconds(15L * 86400)) || kick.isBefore(now.minusSeconds(6 * 3600))) return null
        val (lat, lon) = place(ctx, v) ?: return null
        val key = "%.2f,%.2f".format(java.util.Locale.US, lat, lon)
        val root = fcLock.withLock {
            forecasts[key]?.takeIf { System.currentTimeMillis() - it.first < 3600_000L }?.second
                ?: runCatching {
                    Net.getJson(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&hourly=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit&timezone=GMT&forecast_days=16",
                    )
                }.getOrNull()?.also { forecasts[key] = System.currentTimeMillis() to it }
        } ?: return null
        return pickHour(root, kick)
    }
}

/** Forecast for the right end of a game card's status line, sized to match labelMedium text. */
@Composable
fun GameWeatherTag(g: Game, compact: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val w by produceState(g.venue?.takeIf { it.indoor }?.let { GameWeather(null, "Indoors", Sky.INDOOR) } ?: g.weather, g.id, g.weather, g.venue) {
        if (value == null && g.venue != null) value = WeatherData.forecast(ctx, g.venue, g.date)
    }
    val weather = w ?: return
    val style = MaterialTheme.typography.labelMedium
    val iconSize = with(LocalDensity.current) { (style.fontSize * 1.3f).toDp() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("  •  ", style = style, color = color, maxLines = 1)
        WeatherIcon(weather.sky, weather.night, Modifier.size(iconSize))
        val text = when {
            weather.sky == Sky.INDOOR -> if (compact) "" else "Indoors"
            compact -> weather.tempF?.let { "$it°" } ?: ""
            else -> listOfNotNull(weather.tempF?.let { "$it°" }, weather.text.takeIf { it.isNotBlank() }).joinToString(" ")
        }
        if (text.isNotEmpty()) {
            Text(" $text", style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val SunColor = Color(0xFFFFB300)
private val MoonColor = Color(0xFFCFD8DC)
private val CloudColor = Color(0xFF9EAAB8)
private val RainColor = Color(0xFF42A5F5)
private val BoltColor = Color(0xFFFFC107)
private val SnowColor = Color(0xFFB3E5FC)

/** Small drawn weather icon, so every condition matches in size and weight. */
@Composable
fun WeatherIcon(sky: Sky, night: Boolean, modifier: Modifier = Modifier) {
    val roof = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val s = size.minDimension
        when (sky) {
            Sky.CLEAR -> if (night) moon(Offset(s * 0.5f, s * 0.5f), s * 0.36f) else sun(Offset(s * 0.5f, s * 0.5f), s * 0.22f, s)
            Sky.PARTLY -> {
                if (night) moon(Offset(s * 0.62f, s * 0.36f), s * 0.26f) else sun(Offset(s * 0.64f, s * 0.36f), s * 0.16f, s * 0.7f)
                cloud(s, top = 0.42f)
            }
            Sky.CLOUDY -> cloud(s, top = 0.28f)
            Sky.WIND -> {
                val st = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
                for ((y, len) in listOf(0.32f to 0.7f, 0.52f to 0.85f, 0.72f to 0.55f)) {
                    drawLine(CloudColor, Offset(s * 0.1f, s * y), Offset(s * (0.1f + len), s * y), st.width, StrokeCap.Round)
                }
            }
            Sky.FOG -> {
                cloud(s, top = 0.12f, bottom = 0.56f)
                for (y in listOf(0.7f, 0.86f)) drawLine(CloudColor, Offset(s * 0.12f, s * y), Offset(s * 0.88f, s * y), s * 0.08f, StrokeCap.Round)
            }
            Sky.RAIN -> {
                cloud(s, top = 0.08f, bottom = 0.58f)
                for (x in listOf(0.3f, 0.52f, 0.74f)) drawLine(RainColor, Offset(s * x, s * 0.7f), Offset(s * (x - 0.07f), s * 0.94f), s * 0.08f, StrokeCap.Round)
            }
            Sky.STORM -> {
                cloud(s, top = 0.08f, bottom = 0.58f)
                val p = Path().apply {
                    moveTo(s * 0.56f, s * 0.56f); lineTo(s * 0.38f, s * 0.8f); lineTo(s * 0.52f, s * 0.8f)
                    lineTo(s * 0.44f, s * 1.0f); lineTo(s * 0.68f, s * 0.72f); lineTo(s * 0.54f, s * 0.72f); close()
                }
                drawPath(p, BoltColor)
            }
            Sky.SNOW -> {
                cloud(s, top = 0.08f, bottom = 0.58f)
                for (x in listOf(0.3f, 0.52f, 0.74f)) drawCircle(SnowColor, s * 0.06f, Offset(s * x, s * 0.8f))
            }
            Sky.INDOOR -> {
                // A domed roof over a field line.
                val st = Stroke(width = s * 0.1f, cap = StrokeCap.Round)
                drawArc(roof, 180f, 180f, false, Offset(s * 0.1f, s * 0.22f), Size(s * 0.8f, s * 0.8f), style = st)
                drawLine(roof, Offset(s * 0.06f, s * 0.62f), Offset(s * 0.94f, s * 0.62f), st.width, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, s: Float) {
    drawCircle(SunColor, r, c)
    val st = s * 0.08f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val inner = r * 1.45f
        val outer = r * 2.05f
        drawLine(
            SunColor,
            Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
            Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
            st, StrokeCap.Round,
        )
    }
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.35f), r * 0.85f)) }
    val crescent = Path().apply { op(disc, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(crescent, MoonColor)
}

/** Cloud filling the icon width between [top] and [bottom] (fractions of the icon size). */
private fun DrawScope.cloud(s: Float, top: Float, bottom: Float = 0.86f) {
    val h = (bottom - top) * s
    val base = Offset(s * 0.08f, top * s + h * 0.45f)
    drawRoundRect(CloudColor, base, Size(s * 0.84f, h * 0.55f), CornerRadius(h * 0.275f))
    drawCircle(CloudColor, h * 0.36f, Offset(s * 0.36f, top * s + h * 0.46f))
    drawCircle(CloudColor, h * 0.46f, Offset(s * 0.6f, top * s + h * 0.46f))
}
