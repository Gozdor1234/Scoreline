package com.nate.scoreline

import org.json.JSONObject
import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class F1Entry(val pos: Int, val driver: String, val flag: String, val winner: Boolean)

data class F1Session(
    val id: String,
    val name: String,
    val date: String,
    val state: String,
    val detail: String,
    val results: List<F1Entry>,
) {
    val isLive get() = state == "in"
}

data class F1Weekend(val id: String, val name: String, val circuit: String, val location: String, val date: String, val sessions: List<F1Session>)

data class F1ResultRow(
    val pos: String,
    val driver: String,
    val code: String,
    val team: String,
    val grid: String,
    val laps: String,
    val timeOrStatus: String,
    val points: String,
    val fastestLap: Boolean,
)

data class F1Race(val name: String, val round: String, val circuit: String, val date: String, val results: List<F1ResultRow>)

/** One round of the season schedule. dateIso is UTC start ("2026-09-26T11:00:00Z") or just the date. */
data class F1Round(
    val round: String,
    val name: String,
    val circuit: String,
    val location: String,
    val dateIso: String,
    val hasSprint: Boolean,
    val winner: String? = null,
    val winnerTeam: String? = null,
)

data class F1DriverStanding(val pos: String, val name: String, val code: String, val team: String, val points: String, val wins: String)
data class F1TeamStanding(val pos: String, val team: String, val points: String, val wins: String)

/**
 * Live/current-weekend session order comes from ESPN.
 * Standings and detailed classified results come from Jolpica (the community
 * successor to the Ergast F1 API): documented, free, updated after each session.
 */
object F1 {
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/racing/f1/scoreboard"
    private const val JOLPICA = "https://api.jolpi.ca/ergast/f1/current"

    suspend fun weekends(): List<F1Weekend> = parseWeekends(Net.getJson(ESPN))
    suspend fun lastRace(): F1Race? = parseLastRace(Net.getJson("$JOLPICA/last/results.json"))
    /** Full season schedule, with winners filled in for completed rounds. */
    suspend fun season(): List<F1Round> = coroutineScope {
        val sched = async { parseSchedule(Net.getJson("$JOLPICA/races.json?limit=40")) }
        val winners = async { runCatching { parseWinners(Net.getJson("$JOLPICA/results/1.json?limit=40")) }.getOrDefault(emptyMap()) }
        val w = winners.await()
        sched.await().map { r -> w[r.round]?.let { (d, t) -> r.copy(winner = d, winnerTeam = t) } ?: r }
    }
    suspend fun raceResults(round: String): F1Race? = parseLastRace(Net.getJson("$JOLPICA/$round/results.json"))
    suspend fun sprintResults(round: String): F1Race? = parseLastRace(Net.getJson("$JOLPICA/$round/sprint.json"), "SprintResults")
    suspend fun driverStandings(): List<F1DriverStanding> = parseDriverStandings(Net.getJson("$JOLPICA/driverStandings.json"))
    suspend fun teamStandings(): List<F1TeamStanding> = parseTeamStandings(Net.getJson("$JOLPICA/constructorStandings.json"))

    /** Match key for favorite drivers: accent-stripped, lowercased surname. Works across ESPN and Jolpica naming. */
    fun driverKey(fullName: String): String {
        val last = fullName.trim().split(Regex("\\s+")).lastOrNull() ?: return ""
        val stripped = Normalizer.normalize(last, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return stripped.lowercase()
    }

    fun parseWeekends(root: JSONObject): List<F1Weekend> = root.arr("events").objects().map { ev ->
        val circuit = ev.obj("circuit")
        val addr = circuit?.obj("address")
        val location = listOf(addr?.str("city") ?: "", addr?.str("country") ?: "").filter { it.isNotBlank() }.joinToString(", ")
        val sessions = ev.arr("competitions").objects().map { c ->
            val type = c.obj("type")
            val st = c.obj("status")?.obj("type")
            val results = c.arr("competitors").objects().mapNotNull { comp ->
                val ath = comp.obj("athlete") ?: return@mapNotNull null
                F1Entry(
                    pos = comp.optInt("order", 0),
                    driver = ath.str("displayName").ifEmpty { ath.str("fullName") },
                    flag = ath.obj("flag")?.str("href") ?: "",
                    winner = comp.optBoolean("winner", false),
                )
            }.filter { it.pos > 0 }.sortedBy { it.pos }
            F1Session(
                id = c.str("id"),
                name = type?.str("abbreviation")?.ifEmpty { null } ?: type?.str("name") ?: "Session",
                date = c.str("date"),
                state = st?.str("state")?.ifEmpty { null } ?: "pre",
                detail = st?.str("shortDetail")?.ifEmpty { null } ?: st?.str("description") ?: "",
                results = results,
            )
        }
        F1Weekend(
            id = ev.str("id"),
            name = ev.str("name"),
            circuit = circuit?.str("fullName") ?: "",
            location = location,
            date = ev.str("date"),
            sessions = sessions,
        )
    }

    private fun raceTable(root: JSONObject) = root.obj("MRData")?.obj("RaceTable")?.arr("Races").objects()
    private fun standingsList(root: JSONObject) =
        root.obj("MRData")?.obj("StandingsTable")?.arr("StandingsLists").objects()?.firstOrNull()

    fun parseLastRace(root: JSONObject, key: String = "Results"): F1Race? {
        val race = raceTable(root).firstOrNull() ?: return null
        val rows = race.arr(key).objects().map { r ->
            val d = r.obj("Driver")
            val time = r.obj("Time")?.str("time") ?: ""
            F1ResultRow(
                pos = r.str("position"),
                driver = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
                code = d?.str("code") ?: "",
                team = r.obj("Constructor")?.str("name") ?: "",
                grid = r.str("grid"),
                laps = r.str("laps"),
                timeOrStatus = time.ifEmpty { r.str("status") },
                points = r.str("points"),
                fastestLap = r.obj("FastestLap")?.str("rank") == "1",
            )
        }
        return F1Race(
            name = race.str("raceName"),
            round = race.str("round"),
            circuit = race.obj("Circuit")?.str("circuitName") ?: "",
            date = race.str("date"),
            results = rows,
        )
    }

    fun parseSchedule(root: JSONObject): List<F1Round> = raceTable(root).map { r ->
        val c = r.obj("Circuit")
        val loc = c?.obj("Location")
        val time = r.str("time")
        F1Round(
            round = r.str("round"),
            name = r.str("raceName"),
            circuit = c?.str("circuitName") ?: "",
            location = listOf(loc?.str("locality") ?: "", loc?.str("country") ?: "").filter { it.isNotBlank() }.joinToString(", "),
            dateIso = if (time.isNotEmpty()) "${r.str("date")}T$time" else r.str("date"),
            hasSprint = r.obj("Sprint") != null,
        )
    }

    /** round -> (winner name, team) from a results/1 query. */
    fun parseWinners(root: JSONObject): Map<String, Pair<String, String>> = raceTable(root).mapNotNull { r ->
        val res = r.arr("Results").objects().firstOrNull() ?: return@mapNotNull null
        val d = res.obj("Driver")
        r.str("round") to Pair(
            "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
            res.obj("Constructor")?.str("name") ?: "",
        )
    }.toMap()

    fun parseDriverStandings(root: JSONObject): List<F1DriverStanding> =
        standingsList(root)?.arr("DriverStandings").objects().map { s ->
            val d = s.obj("Driver")
            F1DriverStanding(
                pos = s.str("position").ifEmpty { s.str("positionText") },
                name = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim(),
                code = d?.str("code") ?: "",
                team = s.arr("Constructors").objects().lastOrNull()?.str("name") ?: "",
                points = s.str("points"),
                wins = s.str("wins"),
            )
        }

    fun parseTeamStandings(root: JSONObject): List<F1TeamStanding> =
        standingsList(root)?.arr("ConstructorStandings").objects().map { s ->
            F1TeamStanding(
                pos = s.str("position").ifEmpty { s.str("positionText") },
                team = s.obj("Constructor")?.str("name") ?: "",
                points = s.str("points"),
                wins = s.str("wins"),
            )
        }
}

/**
 * Team badge styling. No free, stable source publishes F1 team logo images, so the app
 * draws a badge in the team's color with a short code. Matching is by keyword so it
 * tolerates Jolpica's naming ("Haas F1 Team", "RB F1 Team", "Alpine F1 Team"...).
 */
object F1Teams {
    /** slug is formula1.com's team folder name, used for the official logo images. */
    data class Style(val code: String, val color: Long, val slug: String? = null)

    private val styles = listOf(
        listOf("mercedes") to Style("MER", 0xFF00A19C, "mercedes"),
        listOf("ferrari") to Style("FER", 0xFFE8002D, "ferrari"),
        listOf("mclaren") to Style("MCL", 0xFFFF8000, "mclaren"),
        listOf("racing bulls", "rb f1", "visa", "alphatauri") to Style("RB", 0xFF6692FF, "racingbulls"),
        listOf("red bull") to Style("RBR", 0xFF3671C6, "redbullracing"),
        listOf("aston") to Style("AMR", 0xFF229971, "astonmartin"),
        listOf("alpine") to Style("ALP", 0xFF0093CC, "alpine"),
        listOf("williams") to Style("WIL", 0xFF1868DB, "williams"),
        listOf("haas") to Style("HAA", 0xFF9C9FA2, "haasf1team"),
        listOf("audi", "sauber", "kick") to Style("AUD", 0xFFBB0A30, "audi"),
        listOf("cadillac") to Style("CAD", 0xFF5A5A5A, "cadillac"),
    )

    /**
     * Official logo image URLs from formula1.com's media server, newest season first.
     * white = the all-white version for dark themes; otherwise the full-color logo.
     */
    fun logoUrls(team: String, white: Boolean, year: Int): List<String> {
        val slug = style(team).slug ?: return emptyList()
        val variant = if (white) "logowhite" else "logo"
        return listOf(year, 2026).distinct().map { y ->
            "https://media.formula1.com/image/upload/c_fit,h_96/q_auto/v1740000000/common/f1/$y/$slug/$y$slug$variant.webp"
        }
    }

    fun style(team: String): Style {
        val t = team.lowercase()
        return styles.firstOrNull { (keys, _) -> keys.any { it in t } }?.second
            ?: Style(team.filter { it.isLetter() }.take(3).uppercase().ifEmpty { "F1" }, 0xFF6B7280)
    }
}
