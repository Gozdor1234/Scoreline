package com.nate.scoreline

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject

/** One race weekend for a driver: grand prix finish plus any sprint points that weekend. */
data class F1DriverRace(
    val round: String,
    val raceName: String,
    val date: String,
    val grid: String,
    /** "1", "12", or "R"/"D"/"W" style codes from positionText. */
    val posText: String,
    val status: String,
    val team: String,
    val racePoints: Double,
    val sprintPos: String?,
    val sprintPoints: Double,
) {
    val points get() = racePoints + sprintPoints
    val finished get() = posText.toIntOrNull() != null
}

/** One race weekend for a team: each driver's finish and the team's points that weekend. */
data class F1TeamRace(
    val round: String,
    val raceName: String,
    val date: String,
    /** (driver name, driverId, finish text) */
    val drivers: List<Triple<String, String, String>>,
    val points: Double,
    val hasSprint: Boolean = false,
)

data class F1DriverProfile(
    val id: String,
    val name: String,
    val code: String,
    val number: String,
    val dob: String,
    val nationality: String,
    val team: String,
    val constructorId: String,
    val standingPos: String,
    val points: String,
    val wins: String,
    val races: List<F1DriverRace>,
)

data class F1TeamProfile(
    val id: String,
    val name: String,
    val nationality: String,
    val standingPos: String,
    val points: String,
    val wins: String,
    val races: List<F1TeamRace>,
    /** (name, driverId) for everyone who raced for the team this season, most starts first. */
    val drivers: List<Pair<String, String>>,
)

/** Career numbers from Jolpica's "total" counts. */
data class F1Career(val wins: Int?, val poles: Int?, val titles: Int?, val starts: Int?)

object F1ProfileParse {
    private fun name(d: JSONObject?) = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim()
    private fun num(s: String) = s.toDoubleOrNull() ?: 0.0

    fun total(root: JSONObject): Int? = root.obj("MRData")?.str("total")?.toIntOrNull()

    /** Merges a driver's grand prix results with their sprint results by round. */
    fun driverRaces(results: JSONObject, sprints: JSONObject?): List<F1DriverRace> {
        val sprintByRound = sprints?.let { F1.raceTable(it) }.orEmpty().associate { r ->
            val sr = r.arr("SprintResults").objects().firstOrNull()
            r.str("round") to (sr?.str("positionText") to num(sr?.str("points") ?: ""))
        }
        return F1.raceTable(results).mapNotNull { r ->
            val res = r.arr("Results").objects().firstOrNull() ?: return@mapNotNull null
            val round = r.str("round")
            val sp = sprintByRound[round]
            F1DriverRace(
                round = round,
                raceName = r.str("raceName"),
                date = r.str("date"),
                grid = res.str("grid"),
                posText = res.str("positionText").ifEmpty { res.str("position") },
                status = res.str("status"),
                team = res.obj("Constructor")?.str("name") ?: "",
                racePoints = num(res.str("points")),
                sprintPos = sp?.first,
                sprintPoints = sp?.second ?: 0.0,
            )
        }.sortedBy { it.round.toIntOrNull() ?: 0 }
    }

    /** Team results per round: both drivers' finishes; points include that weekend's sprint. */
    fun teamRaces(results: JSONObject, sprints: JSONObject?): List<F1TeamRace> {
        val sprintPts = sprints?.let { F1.raceTable(it) }.orEmpty().associate { r ->
            r.str("round") to r.arr("SprintResults").objects().sumOf { num(it.str("points")) }
        }
        return F1.raceTable(results).map { r ->
            val res = r.arr("Results").objects()
            val round = r.str("round")
            F1TeamRace(
                round = round,
                raceName = r.str("raceName"),
                date = r.str("date"),
                drivers = res.map { x ->
                    val d = x.obj("Driver")
                    Triple(name(d), d?.str("driverId") ?: "", x.str("positionText").ifEmpty { x.str("position") })
                },
                points = res.sumOf { num(it.str("points")) } + (sprintPts[round] ?: 0.0),
                hasSprint = round in sprintPts,
            )
        }.sortedBy { it.round.toIntOrNull() ?: 0 }
    }

    fun teamDrivers(results: JSONObject): List<Pair<String, String>> =
        F1.raceTable(results).flatMap { r -> r.arr("Results").objects().map { it.obj("Driver") } }
            .filterNotNull()
            .groupBy { it.str("driverId") }
            .entries.sortedByDescending { it.value.size }
            .map { (id, list) -> name(list.first()) to id }

    fun driverInfo(root: JSONObject): JSONObject? =
        root.obj("MRData")?.obj("DriverTable")?.arr("Drivers").objects().firstOrNull()

    fun constructorInfo(root: JSONObject): JSONObject? =
        root.obj("MRData")?.obj("ConstructorTable")?.arr("Constructors").objects().firstOrNull()

    /** "P1" for a number, else a readable reason ("DNF", "DSQ", ...). */
    fun finishLabel(posText: String): String = when {
        posText.toIntOrNull() != null -> "P$posText"
        posText == "R" -> "DNF"
        posText == "D" -> "DSQ"
        posText == "W" -> "DNS"
        posText == "N" -> "NC"
        posText == "E" -> "EX"
        posText == "F" -> "DNQ"
        else -> posText.ifEmpty { "-" }
    }

    fun fmtPoints(p: Double): String = if (p == Math.floor(p)) p.toLong().toString() else p.toString()
}

object F1Profiles {
    private const val BASE = "https://api.jolpi.ca/ergast/f1"
    private const val CUR = "$BASE/current"

    suspend fun driver(id: String): F1DriverProfile = coroutineScope {
        val info = async { Net.getJson("$BASE/drivers/$id.json") }
        val results = async { Net.getJson("$CUR/drivers/$id/results.json?limit=100") }
        val sprints = async { runCatching { Net.getJson("$CUR/drivers/$id/sprint.json?limit=100") }.getOrNull() }
        val standing = async { runCatching { Net.getJson("$CUR/drivers/$id/driverStandings.json") }.getOrNull() }
        val d = F1ProfileParse.driverInfo(info.await())
        val races = F1ProfileParse.driverRaces(results.await(), sprints.await())
        val st = standing.await()?.let { F1.parseDriverStandings(it).firstOrNull() }
        F1DriverProfile(
            id = id,
            name = "${d?.str("givenName") ?: ""} ${d?.str("familyName") ?: ""}".trim().ifEmpty { st?.name ?: id },
            code = d?.str("code") ?: "",
            number = d?.str("permanentNumber") ?: "",
            dob = d?.str("dateOfBirth") ?: "",
            nationality = d?.str("nationality") ?: "",
            team = st?.team ?: races.lastOrNull()?.team ?: "",
            constructorId = st?.constructorId ?: "",
            standingPos = st?.pos ?: "",
            points = st?.points ?: F1ProfileParse.fmtPoints(races.sumOf { it.points }),
            wins = st?.wins ?: races.count { it.posText == "1" }.toString(),
            races = races,
        )
    }

    suspend fun team(id: String): F1TeamProfile = coroutineScope {
        val info = async { Net.getJson("$BASE/constructors/$id.json") }
        val results = async { Net.getJson("$CUR/constructors/$id/results.json?limit=100") }
        val sprints = async { runCatching { Net.getJson("$CUR/constructors/$id/sprint.json?limit=100") }.getOrNull() }
        val standing = async { runCatching { Net.getJson("$CUR/constructors/$id/constructorStandings.json") }.getOrNull() }
        val c = F1ProfileParse.constructorInfo(info.await())
        val res = results.await()
        val races = F1ProfileParse.teamRaces(res, sprints.await())
        val st = standing.await()?.let { F1.parseTeamStandings(it).firstOrNull() }
        F1TeamProfile(
            id = id,
            name = c?.str("name")?.ifEmpty { null } ?: st?.team ?: id,
            nationality = c?.str("nationality") ?: "",
            standingPos = st?.pos ?: "",
            points = st?.points ?: F1ProfileParse.fmtPoints(races.sumOf { it.points }),
            wins = st?.wins ?: "",
            races = races,
            drivers = F1ProfileParse.teamDrivers(res),
        )
    }

    /** Career totals. Called after the season data, spaced out to respect Jolpica's rate limit. */
    suspend fun driverCareer(id: String): F1Career = coroutineScope {
        delay(1200)
        val wins = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/drivers/$id/results/1.json?limit=1")) }.getOrNull() }
        val poles = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/drivers/$id/qualifying/1.json?limit=1")) }.getOrNull() }
        val titles = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/drivers/$id/driverStandings/1.json?limit=1")) }.getOrNull() }
        val starts = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/drivers/$id/results.json?limit=1")) }.getOrNull() }
        F1Career(wins.await(), poles.await(), titles.await(), starts.await())
    }

    suspend fun teamCareer(id: String): F1Career = coroutineScope {
        delay(1200)
        val wins = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/constructors/$id/results/1.json?limit=1")) }.getOrNull() }
        val poles = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/constructors/$id/qualifying/1.json?limit=1")) }.getOrNull() }
        val titles = async { runCatching { F1ProfileParse.total(Net.getJson("$BASE/constructors/$id/constructorStandings/1.json?limit=1")) }.getOrNull() }
        F1Career(wins.await(), poles.await(), titles.await(), null)
    }

    /**
     * Official formula1.com driver photo for this season's team. The file name is the driver
     * code formula1.com uses: first 3 letters of the first name + first 3 of the surname + "01"
     * (Franco Colapinto -> fracol01).
     */
    fun officialPhoto(fullName: String, team: String, year: Int): String? {
        val slug = F1Teams.style(team).slug ?: return null
        fun clean(w: String) = java.text.Normalizer.normalize(w, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().filter { it in 'a'..'z' }
        val words = fullName.trim().split(Regex("\\s+")).map(::clean).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val code = words.first().take(3) + words.last().take(3) + "01"
        return "https://media.formula1.com/image/upload/c_lfill,w_480/q_auto/v1740000000/common/f1/$year/$slug/$code/$year$slug${code}right.webp"
    }

    /** Driver photo from OpenF1's latest session, matched by surname. Null if unavailable. */
    private var headshots: Map<String, String>? = null
    suspend fun headshot(fullName: String): String? {
        val map = headshots ?: runCatching {
            val arr = org.json.JSONArray(
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val c = java.net.URL("https://api.openf1.org/v1/drivers?session_key=latest").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 12000
                    try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                },
            )
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.str("headshot_url")
                if (url.isEmpty()) null else F1.driverKey(o.str("last_name")) to url
            }.toMap()
        }.getOrNull()?.also { headshots = it }
        return map?.get(F1.driverKey(fullName))
    }
}
