package com.nate.scoreline

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Meeting-wide stints -> session_key -> driverKey -> compounds in fitting order. */
    fun tyresBySession(stints: JSONArray, numbers: Map<Int, String>): Map<Int, Map<String, List<Tyre>>> {
        val bySession = HashMap<Int, JSONArray>()
        for (i in 0 until stints.length()) {
            val o = stints.optJSONObject(i) ?: continue
            val k = o.optInt("session_key", 0)
            if (k > 0) bySession.getOrPut(k) { JSONArray("[]") }.put(o)
        }
        return bySession.mapValues { (_, arr) -> tyres(arr, numbers) }
    }

    /** Compact text form for the on-device cache: "russell=MEDIUM,SOFT|verstappen=..." */
    fun encodeTyres(m: Map<String, List<Tyre>>): String =
        m.entries.joinToString("|") { (k, v) -> k + "=" + v.joinToString(",") { it.name } }

    fun decodeTyres(s: String): Map<String, List<Tyre>> =
        s.split('|').filter { '=' in it }.associate { e ->
            e.substringBefore('=') to e.substringAfter('=').split(',').mapNotNull { n -> Tyre.entries.firstOrNull { it.name == n } }
        }

    fun encodePoints(m: Map<String, String>): String = m.entries.joinToString("|") { "${it.key}=${it.value}" }
    fun decodePoints(s: String): Map<String, String> =
        s.split('|').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

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
 * Fetches and caches the extras.
 *
 * Speed: OpenF1 is asked for the whole race weekend at once (one call for car numbers, one for
 * every session's tyre stints, run in parallel), and Jolpica points load alongside. Results for
 * finished sessions are also saved on the phone, so reopening the F1 tab shows them instantly.
 * OpenF1's free tier doesn't serve data during a live session; tyres then appear after it ends.
 */
object F1Extras {
    private const val OPENF1 = "https://api.openf1.org/v1"
    private const val JOLPICA = "https://api.jolpi.ca/ergast/f1"
    private const val DAY_MS = 24 * 3600_000L

    private var prefs: android.content.SharedPreferences? = null

    /** Call once with any Context before load(); enables the on-phone cache. */
    fun init(ctx: android.content.Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("f1_extras", android.content.Context.MODE_PRIVATE)
    }

    private val mem = HashMap<String, String>()
    private fun cached(key: String): String? = mem[key] ?: prefs?.getString(key, null)?.also { mem[key] = it }
    private fun save(key: String, value: String, disk: Boolean) {
        mem[key] = value
        if (disk) prefs?.edit()?.putString(key, value)?.apply()
    }
    /** Time-limited text cache (value stored with its save time). */
    private fun cachedFresh(key: String, maxAgeMs: Long): String? {
        val raw = cached(key) ?: return null
        val at = raw.substringBefore('\n').toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - at < maxAgeMs) raw.substringAfter('\n') else null
    }
    private fun saveFresh(key: String, value: String) = save(key, "${System.currentTimeMillis()}\n$value", disk = true)

    private var lastMeetingFetch = HashMap<Int, Long>()

    suspend fun load(w: F1Weekend): Map<String, SessionExtra> = coroutineScope {
        val started = w.sessions.filter { it.state != "pre" }
        if (started.isEmpty()) return@coroutineScope emptyMap()
        val year = F1ExtrasParse.instant(started.first().date)?.atOffset(ZoneOffset.UTC)?.year ?: return@coroutineScope emptyMap()

        // Points (Jolpica) load in parallel with tyres (OpenF1).
        val pointsJobs = started.associate { s -> s.id to async { pointsFor(s, year) } }

        val oSessions = runCatching { openF1Sessions(year) }.getOrDefault(emptyList())
        val matched = started.associateWith { F1ExtrasParse.match(oSessions, it.date) }
        val tyresBySession = HashMap<Int, Map<String, List<Tyre>>>()
        matched.forEach { (s, o) ->
            if (o != null && s.state == "post") cached("tyres-${o.key}")?.let { tyresBySession[o.key] = F1ExtrasParse.decodeTyres(it) }
        }
        val missing = matched.filter { (_, o) -> o != null && o.key !in tyresBySession }
        val meetingKey = missing.values.firstNotNullOfOrNull { it?.meetingKey }
        val recently = meetingKey?.let { System.currentTimeMillis() - (lastMeetingFetch[it] ?: 0L) < 60_000L } ?: true
        if (meetingKey != null && !recently) {
            lastMeetingFetch[meetingKey] = System.currentTimeMillis()
            runCatching {
                val numbersJob = async {
                    cachedFresh("drivers-$meetingKey", DAY_MS)
                        ?.let { t -> t.split('|').filter { ':' in it }.associate { it.substringBefore(':').toInt() to it.substringAfter(':') } }
                        ?: F1ExtrasParse.driverNumbers(getArray("$OPENF1/drivers?meeting_key=$meetingKey")).also { m ->
                            if (m.isNotEmpty()) saveFresh("drivers-$meetingKey", m.entries.joinToString("|") { "${it.key}:${it.value}" })
                        }
                }
                val stintsJob = async { getArray("$OPENF1/stints?meeting_key=$meetingKey") }
                val all = F1ExtrasParse.tyresBySession(stintsJob.await(), numbersJob.await())
                all.forEach { (sessionKey, t) ->
                    tyresBySession[sessionKey] = t
                    val finished = matched.any { (s, o) -> o?.key == sessionKey && s.state == "post" }
                    if (finished && t.isNotEmpty()) save("tyres-$sessionKey", F1ExtrasParse.encodeTyres(t), disk = true)
                }
            }
        }

        val out = HashMap<String, SessionExtra>()
        for (s in started) {
            val o = matched[s]
            val tyres = o?.let { tyresBySession[it.key] } ?: emptyMap()
            out[s.id] = SessionExtra(tyres, pointsJobs.getValue(s.id).await())
        }
        out
    }

    /** Official points for a finished race/sprint, or table points until Jolpica posts them. */
    private suspend fun pointsFor(s: F1Session, year: Int): Map<String, String>? {
        val kind = F1ExtrasParse.scoringKind(null, s.name) ?: return null
        if (s.state != "post") return null
        val sprint = kind == "sprint"
        return runCatching { officialPoints(s.date, sprint, year) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: F1ExtrasParse.tablePoints(s.results, sprint)
    }

    private suspend fun openF1Sessions(year: Int): List<F1ExtrasParse.OSession> {
        cachedFresh("sessions-$year", 12 * 3600_000L)?.let { return F1ExtrasParse.sessions(JSONArray(it)) }
        val arr = getArray("$OPENF1/sessions?year=$year")
        val list = F1ExtrasParse.sessions(arr)
        if (list.isNotEmpty()) saveFresh("sessions-$year", arr.toString())
        return list
    }

    private suspend fun officialPoints(espnDate: String, sprint: Boolean, year: Int): Map<String, String> {
        val day = F1ExtrasParse.utcDate(espnDate) ?: return emptyMap()
        val sched = cachedFresh("schedule-$year", 12 * 3600_000L)?.let { F1ExtrasParse.schedule(JSONObject(it)) }
            ?: run {
                val root = Net.getJson("$JOLPICA/$year/races.json?limit=40")
                F1ExtrasParse.schedule(root).also { if (it.isNotEmpty()) saveFresh("schedule-$year", root.toString()) }
            }
        // Within a day: Jolpica dates are local race days, which can differ from UTC (e.g. Las Vegas).
        val target = java.time.LocalDate.parse(day)
        val round = sched.firstOrNull { r ->
            val d = (if (sprint) r.sprintDate else r.raceDate)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            d != null && Math.abs(d.toEpochDay() - target.toEpochDay()) <= 1
        }?.round ?: return emptyMap()
        val cacheKey = "pts-$year-$round-$sprint"
        cached(cacheKey)?.let { return F1ExtrasParse.decodePoints(it) }
        val p = F1ExtrasParse.points(Net.getJson("$JOLPICA/$year/$round/${if (sprint) "sprint" else "results"}.json"), sprint)
        if (p.isNotEmpty()) save(cacheKey, F1ExtrasParse.encodePoints(p), disk = true)
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
