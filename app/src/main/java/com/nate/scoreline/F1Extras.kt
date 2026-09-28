package com.nate.scoreline

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tyre compound as the app labels it. */
enum class Tyre(val label: String) { SOFT("S"), MEDIUM("M"), HARD("H"), INTER("Int"), WET("W") }

/**
 * Per-session extras for the weekend cards, keyed by F1.driverKey (accent-stripped surname).
 * tyres: compounds in the order they were fitted. points: null for sessions that don't score.
 */
data class SessionExtra(val tyres: Map<String, List<Tyre>>, val points: Map<String, String>?)

/**
 * Pure parsers for OpenF1 (tyre stints) and Jolpica (official points). Kept free of
 * Android and network code so they can be unit tested on the JVM.
 */
object F1ExtrasParse {
    data class OSession(val key: Int, val meetingKey: Int, val name: String, val start: Instant)
    data class JRound(val round: String, val raceDate: String, val sprintDate: String?)

    /** Race and sprint points by finishing position, used only until Jolpica posts official results. */
    private val RACE_PTS = listOf(25, 18, 15, 12, 10, 8, 6, 4, 2, 1)
    private val SPRINT_PTS = listOf(8, 7, 6, 5, 4, 3, 2, 1)

    fun instant(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

    fun compound(s: String): Tyre? = when (s.trim().uppercase()) {
        "SOFT", "HYPERSOFT", "ULTRASOFT", "SUPERSOFT" -> Tyre.SOFT
        "MEDIUM" -> Tyre.MEDIUM
        "HARD" -> Tyre.HARD
        "INTERMEDIATE", "INTER" -> Tyre.INTER
        "WET", "FULL WET" -> Tyre.WET
        else -> null // UNKNOWN, TEST_UNKNOWN, null
    }

    fun sessions(arr: JSONArray): List<OSession> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val start = instant(o.str("date_start")) ?: return@mapNotNull null
        OSession(o.optInt("session_key", 0), o.optInt("meeting_key", 0), o.str("session_name"), start)
    }.filter { it.key > 0 }

    /** OpenF1 session whose start is closest to ESPN's, within 3 hours. */
    fun match(list: List<OSession>, espnDate: String): OSession? {
        val t = instant(espnDate) ?: return null
        return list.minByOrNull { Math.abs(it.start.epochSecond - t.epochSecond) }
            ?.takeIf { Math.abs(it.start.epochSecond - t.epochSecond) <= 3 * 3600 }
    }

    /** Car number -> driverKey. Later entries win, so a mid-weekend substitute is picked up. */
    fun driverNumbers(arr: JSONArray): Map<Int, String> {
        val m = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optInt("driver_number", 0)
            val last = o.str("last_name").ifEmpty { o.str("full_name") }
            if (n > 0 && last.isNotBlank()) m[n] = F1.driverKey(last)
        }
        return m
    }

    /** Stints -> each driver's compounds in fitting order. Unknown compounds are skipped. */
    fun tyres(stints: JSONArray, numbers: Map<Int, String>): Map<String, List<Tyre>> {
        data class S(val num: Int, val stint: Int, val tyre: Tyre)
        val rows = (0 until stints.length()).mapNotNull { i ->
            val o = stints.optJSONObject(i) ?: return@mapNotNull null
            val t = compound(o.str("compound")) ?: return@mapNotNull null
            S(o.optInt("driver_number", 0), o.optInt("stint_number", 0), t)
        }
        return rows.groupBy { it.num }
            .mapNotNull { (num, list) -> numbers[num]?.let { key -> key to list.sortedBy { it.stint }.map { it.tyre } } }
            .toMap()
    }

    fun schedule(root: JSONObject): List<JRound> =
        root.obj("MRData")?.obj("RaceTable")?.arr("Races").objects().map { r ->
            JRound(r.str("round"), r.str("date"), r.obj("Sprint")?.str("date")?.ifEmpty { null })
        }

    /** driverKey -> official points from a Jolpica results or sprint response. */
    fun points(root: JSONObject, sprint: Boolean): Map<String, String> {
        val race = root.obj("MRData")?.obj("RaceTable")?.arr("Races").objects().firstOrNull() ?: return emptyMap()
        return race.arr(if (sprint) "SprintResults" else "Results").objects().mapNotNull { r ->
            val fam = r.obj("Driver")?.str("familyName") ?: return@mapNotNull null
            F1.driverKey(fam) to r.str("points").ifEmpty { "0" }
        }.toMap()
    }

    /** Points by classified order, for the gap between the flag and Jolpica's update. */
    fun tablePoints(results: List<F1Entry>, sprint: Boolean): Map<String, String> {
        val table = if (sprint) SPRINT_PTS else RACE_PTS
        return results.associate { F1.driverKey(it.driver) to (table.getOrNull(it.pos - 1) ?: 0).toString() }
    }

    /** Race or Sprint (not Sprint Qualifying/Shootout), from OpenF1's name when known, else ESPN's. */
    fun scoringKind(openF1Name: String?, espnName: String): String? {
        val n = (openF1Name ?: espnName).lowercase()
        if ("qual" in n || "shootout" in n || "practice" in n) return null
        return when {
            "sprint" in n -> "sprint"
            n == "race" || n.startsWith("race") || n == "gp" -> "race"
            else -> null
        }
    }

    fun utcDate(iso: String): String? = instant(iso)?.atOffset(ZoneOffset.UTC)?.toLocalDate()?.toString()
}

/**
 * Fetches and caches the extras. Finished sessions are cached for the app's lifetime,
 * so after the first load the weekend card makes no further OpenF1 calls.
 * OpenF1's free tier doesn't serve data during a live session; tyres then appear after it ends.
 */
object F1Extras {
    private const val OPENF1 = "https://api.openf1.org/v1"
    private const val JOLPICA = "https://api.jolpi.ca/ergast/f1"

    private var sessionsYear = 0
    private var sessionsAt = 0L
    private var sessionsCache: List<F1ExtrasParse.OSession> = emptyList()
    private val driversCache = HashMap<Int, Map<Int, String>>()
    private val tyreCache = HashMap<Int, Map<String, List<Tyre>>>()
    private var scheduleCache: Pair<Long, List<F1ExtrasParse.JRound>>? = null
    private val pointsCache = HashMap<String, Map<String, String>>()

    suspend fun load(w: F1Weekend): Map<String, SessionExtra> {
        val started = w.sessions.filter { it.state != "pre" }
        if (started.isEmpty()) return emptyMap()
        val year = F1ExtrasParse.instant(started.first().date)?.atOffset(ZoneOffset.UTC)?.year ?: return emptyMap()
        val oSessions = runCatching { openF1Sessions(year) }.getOrDefault(emptyList())
        val out = HashMap<String, SessionExtra>()
        for (s in started) {
            val o = F1ExtrasParse.match(oSessions, s.date)
            val tyres = if (o != null) runCatching { tyres(o, finished = s.state == "post") }.getOrDefault(emptyMap()) else emptyMap()
            val kind = F1ExtrasParse.scoringKind(o?.name, s.name)
            val points = if (kind != null && s.state == "post") {
                val sprint = kind == "sprint"
                runCatching { officialPoints(s.date, sprint) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: F1ExtrasParse.tablePoints(s.results, sprint)
            } else null
            out[s.id] = SessionExtra(tyres, points)
        }
        return out
    }

    private suspend fun openF1Sessions(year: Int): List<F1ExtrasParse.OSession> {
        val now = System.currentTimeMillis()
        if (year == sessionsYear && now - sessionsAt < 6 * 3600_000L && sessionsCache.isNotEmpty()) return sessionsCache
        val list = F1ExtrasParse.sessions(getArray("$OPENF1/sessions?year=$year"))
        sessionsYear = year; sessionsAt = now; sessionsCache = list
        return list
    }

    private suspend fun tyres(o: F1ExtrasParse.OSession, finished: Boolean): Map<String, List<Tyre>> {
        tyreCache[o.key]?.let { return it }
        val numbers = driversCache[o.meetingKey] ?: run {
            delay(350) // OpenF1 free tier allows about 3 requests per second
            F1ExtrasParse.driverNumbers(getArray("$OPENF1/drivers?meeting_key=${o.meetingKey}"))
                .also { if (it.isNotEmpty()) driversCache[o.meetingKey] = it }
        }
        delay(350)
        val t = F1ExtrasParse.tyres(getArray("$OPENF1/stints?session_key=${o.key}"), numbers)
        if (finished && t.isNotEmpty()) tyreCache[o.key] = t
        return t
    }

    private suspend fun officialPoints(espnDate: String, sprint: Boolean): Map<String, String> {
        val day = F1ExtrasParse.utcDate(espnDate) ?: return emptyMap()
        val year = day.take(4)
        val now = System.currentTimeMillis()
        val sched = scheduleCache?.takeIf { now - it.first < 12 * 3600_000L }?.second
            ?: F1ExtrasParse.schedule(Net.getJson("$JOLPICA/$year/races.json?limit=40")).also { scheduleCache = now to it }
        // Within a day: Jolpica dates are local race days, which can differ from UTC (e.g. Las Vegas).
        val target = java.time.LocalDate.parse(day)
        val round = sched.firstOrNull { r ->
            val d = (if (sprint) r.sprintDate else r.raceDate)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            d != null && Math.abs(d.toEpochDay() - target.toEpochDay()) <= 1
        }?.round ?: return emptyMap()
        val cacheKey = "$year-$round-$sprint"
        pointsCache[cacheKey]?.let { return it }
        val p = F1ExtrasParse.points(Net.getJson("$JOLPICA/$year/$round/${if (sprint) "sprint" else "results"}.json"), sprint)
        if (p.isNotEmpty()) pointsCache[cacheKey] = p
        return p
    }

    private suspend fun getArray(url: String): JSONArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Scoreology/1.0 (personal Android app)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
