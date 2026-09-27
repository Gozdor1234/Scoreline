package com.nate.scoreline

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// ---------------------------------------------------------------- models

data class GolfEntry(
    val athleteId: String,
    val name: String,
    val flag: String,
    val position: String,
    /** Total to par, e.g. "-12" or "E" */
    val toPar: String,
    /** Current round to par */
    val today: String,
    /** "F", "12", or a tee time when not started */
    val thru: String,
    /** Round stroke totals, e.g. ["64", "69"] */
    val rounds: List<String>,
    val sortOrder: Int,
    /** CUT / WD / DQ etc., empty if still playing or finished normally */
    val outStatus: String,
)

data class GolfTeamScore(val name: String, val score: String, val logo: String)

data class GolfTournament(
    val id: String,
    val name: String,
    val start: String,
    val end: String,
    /** pre / in / post */
    val state: String,
    val statusDetail: String,
    val purse: String,
    val defendingChampion: String,
    val major: Boolean,
    val rounds: Int,
    val currentRound: Int,
    val cutRound: Int,
    val cutScore: String,
    val cutCount: Int,
    val entries: List<GolfEntry>,
)

data class GolfCalendarItem(val id: String, val label: String, val start: String, val end: String)

/** What ESPN's PGA scoreboard says is on now, plus the season calendar. */
data class GolfNow(
    val eventId: String,
    val eventName: String,
    /** Team match play (Ryder Cup, Presidents Cup) has team scores instead of a leaderboard. */
    val teamEvent: Boolean,
    val teams: List<GolfTeamScore>,
    val state: String,
    val statusDetail: String,
    val calendar: List<GolfCalendarItem>,
)

data class Golfer(
    val id: String,
    val name: String,
    val headshot: String,
    val flag: String,
    val country: String,
    val age: String,
    val birthDate: String,
    val birthPlace: String,
    val college: String,
    val turnedPro: String,
    val height: String,
    val weight: String,
    val hand: String,
    val summaryTitle: String,
    val summary: List<SummaryStat>,
)

data class GolferResult(
    val eventId: String,
    val name: String,
    val date: String,
    val position: String,
    val toPar: String,
    val total: String,
    val rounds: List<String>,
)

data class GolferOverview(
    val seasonTitle: String,
    val labels: List<String>,
    val rows: List<Pair<String, List<String>>>,
    val recent: List<GolferResult>,
    val news: List<NewsItem>,
)

// ---------------------------------------------------------------- feeds

object Golf {
    private const val SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/golf/pga/scoreboard"
    private const val LEADERBOARD = "https://site.web.api.espn.com/apis/site/v2/sports/golf/leaderboard?league=pga"
    private const val COMMON = "https://site.web.api.espn.com/apis/common/v3/sports/golf/pga"

    suspend fun now(): GolfNow = parseNow(Net.getJson(SCOREBOARD))
    suspend fun tournament(eventId: String?): GolfTournament? =
        parseLeaderboard(Net.getJson(LEADERBOARD + if (eventId != null) "&event=$eventId" else ""))
    suspend fun golfer(id: String): Golfer = parseGolfer(Net.getJson("$COMMON/athletes/$id"))
    suspend fun golferOverview(id: String): GolferOverview = parseGolferOverview(Net.getJson("$COMMON/athletes/$id/overview"))

    fun instant(iso: String): Instant? = try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (e: Exception) {
        try { Instant.parse(iso) } catch (e2: Exception) { null }
    }

    /**
     * Upcoming = hasn't ended yet (includes this week's event); completed = ended, newest first.
     * An event "ends" at the end of its final day, so a Sunday finish still counts as this week.
     */
    fun splitCalendar(items: List<GolfCalendarItem>, now: Instant): Pair<List<GolfCalendarItem>, List<GolfCalendarItem>> {
        fun endOf(i: GolfCalendarItem) = instant(i.end)?.plusSeconds(24 * 3600) ?: instant(i.start)
        val upcoming = items.filter { endOf(it)?.isAfter(now) ?: true }.sortedBy { it.start }
        val done = items.filter { endOf(it)?.isAfter(now) == false }.sortedByDescending { it.start }
        return upcoming to done
    }

    // ------------------------------------------------------------ parsers

    fun parseNow(root: JSONObject): GolfNow {
        val ev = root.arr("events").objects().firstOrNull()
        val comp = ev?.arr("competitions").objects().firstOrNull()
        val competitors = comp?.arr("competitors").objects().orEmpty()
        val team = competitors.isNotEmpty() && competitors.all { it.str("type") == "team" || it.has("team") && !it.has("athlete") }
        val statusType = (comp?.obj("status") ?: ev?.obj("status"))?.obj("type")
        val calendar = root.arr("leagues").objects().firstOrNull()?.arr("calendar").objects().orEmpty().mapNotNull { c ->
            val id = c.str("id").ifEmpty { c.obj("event")?.str("\$ref")?.substringAfterLast("/events/")?.substringBefore("?").orEmpty() }
            if (id.isEmpty()) null else GolfCalendarItem(id, c.str("label"), c.str("startDate"), c.str("endDate"))
        }
        return GolfNow(
            eventId = ev?.str("id").orEmpty(),
            eventName = ev?.str("name").orEmpty(),
            teamEvent = team,
            teams = if (!team) emptyList() else competitors.map { c ->
                val t = c.obj("team")
                GolfTeamScore(
                    name = t?.str("displayName").orEmpty().ifEmpty { t?.str("name").orEmpty() },
                    score = c.optJSONObject("score")?.str("displayValue") ?: c.str("score"),
                    logo = t?.str("logo").orEmpty(),
                )
            },
            state = statusType?.str("state").orEmpty(),
            statusDetail = statusType?.str("shortDetail").orEmpty().ifEmpty { statusType?.str("detail").orEmpty() },
            calendar = calendar,
        )
    }

    private val outStatuses = mapOf("STATUS_CUT" to "CUT", "STATUS_WITHDRAWN" to "WD", "STATUS_DISQUALIFIED" to "DQ", "STATUS_MDF" to "MDF")

    fun parseLeaderboard(root: JSONObject): GolfTournament? {
        val ev = root.arr("events").objects().firstOrNull() ?: return null
        val comp = ev.arr("competitions").objects().firstOrNull()
        val t = ev.obj("tournament")
        val compStatus = comp?.obj("status")
        val statusType = compStatus?.obj("type") ?: ev.obj("status")?.obj("type")
        val currentRound = compStatus?.optInt("period", 0) ?: 0
        val entries = comp?.arr("competitors").objects().orEmpty().mapNotNull { c ->
            val a = c.obj("athlete") ?: return@mapNotNull null
            val st = c.obj("status")
            val lines = c.arr("linescores").objects()
            val todayLine = lines.firstOrNull { it.optInt("period", -1) == (st?.optInt("period", 0) ?: 0) }
            val stType = st?.obj("type")?.str("name").orEmpty()
            val out = outStatuses[stType].orEmpty()
            val thruRaw = st?.str("displayThru").orEmpty()
            val thru = when {
                st?.obj("type")?.str("state") == "pre" -> Golf.teeTime(st.str("teeTime"))
                st?.obj("type")?.optBoolean("completed", false) == true && (thruRaw == "18" || thruRaw.isEmpty()) -> "F"
                else -> thruRaw.ifEmpty { st?.str("displayValue").orEmpty() }
            }
            GolfEntry(
                athleteId = a.str("id").ifEmpty { c.str("id") },
                name = a.str("displayName"),
                flag = a.obj("flag")?.str("href").orEmpty(),
                position = out.ifEmpty { st?.obj("position")?.str("displayName").orEmpty() },
                toPar = c.optJSONObject("score")?.str("displayValue") ?: c.str("score"),
                today = if (out.isNotEmpty()) "" else todayLine?.str("displayValue").orEmpty(),
                thru = if (out.isNotEmpty()) "" else thru,
                rounds = lines.filter { it.has("value") }.sortedBy { it.optInt("period", 0) }.map { it.numText("value") },
                sortOrder = c.optInt("sortOrder", Int.MAX_VALUE),
                outStatus = out,
            )
        }.sortedWith(compareBy<GolfEntry>({ if (it.outStatus.isNotEmpty()) 1 else 0 }, { it.sortOrder }))
        return GolfTournament(
            id = ev.str("id"),
            name = ev.str("name"),
            start = ev.str("date"),
            end = ev.str("endDate"),
            state = statusType?.str("state").orEmpty().ifEmpty { "pre" },
            statusDetail = statusType?.str("shortDetail").orEmpty().ifEmpty { statusType?.str("detail").orEmpty() },
            purse = ev.str("displayPurse"),
            defendingChampion = ev.obj("defendingChampion")?.let { d ->
                d.obj("athlete")?.str("displayName").orEmpty().ifEmpty { d.str("displayName") }
            }.orEmpty(),
            major = t?.optBoolean("major", false) ?: false,
            rounds = t?.optInt("numberOfRounds", 4) ?: 4,
            currentRound = currentRound,
            cutRound = t?.optInt("cutRound", 0) ?: 0,
            cutScore = t?.numText("cutScore").orEmpty(),
            cutCount = t?.optInt("cutCount", 0) ?: 0,
            entries = entries,
        )
    }

    private val teeFmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a")

    /** Tee time in the phone's time zone, e.g. "8:40 AM". */
    fun teeTime(iso: String): String = instant(iso)?.atZone(java.time.ZoneId.systemDefault())?.format(teeFmt).orEmpty()

    fun parseGolfer(root: JSONObject): Golfer {
        val a = root.obj("athlete") ?: root
        val summary = a.obj("statsSummary")
        return Golfer(
            id = a.str("id"),
            name = a.str("displayName"),
            headshot = a.obj("headshot")?.str("href").orEmpty(),
            flag = a.obj("flag")?.str("href").orEmpty(),
            country = a.str("citizenship").ifEmpty { a.obj("flag")?.str("alt").orEmpty() },
            age = a.numText("age"),
            birthDate = a.str("displayDOB"),
            birthPlace = a.str("displayBirthPlace").ifEmpty {
                a.obj("birthPlace")?.let { bp -> listOf(bp.str("city"), bp.str("state"), bp.str("country")).filter { it.isNotBlank() }.joinToString(", ") }.orEmpty()
            },
            college = a.obj("college")?.str("name").orEmpty(),
            turnedPro = a.numText("turnedPro").ifEmpty { a.numText("proYear") },
            height = a.str("displayHeight"),
            weight = a.str("displayWeight"),
            hand = a.obj("hand")?.str("displayValue").orEmpty().ifEmpty { a.str("hand") },
            summaryTitle = summary?.str("displayName").orEmpty(),
            summary = summary?.arr("statistics").objects().orEmpty().map { st ->
                SummaryStat(
                    label = st.str("shortDisplayName").ifEmpty { st.str("abbreviation") }.ifEmpty { st.str("displayName") },
                    value = st.str("displayValue"),
                    rank = st.str("rankDisplayValue"),
                )
            },
        )
    }

    fun parseGolferOverview(root: JSONObject): GolferOverview {
        val st = root.obj("statistics")
        val recent = root.arr("recentTournaments").objects().flatMap { it.arr("eventsStats").objects() }.mapNotNull { e ->
            val c = e.arr("competitions").objects().firstOrNull()?.arr("competitors").objects()?.firstOrNull()
                ?: return@mapNotNull null
            val lines = c.optJSONObject("linescores")?.arr("items").objects().ifEmpty { c.arr("linescores").objects() }
            GolferResult(
                eventId = e.str("id"),
                name = e.str("name"),
                date = e.str("date"),
                position = c.obj("status")?.obj("position")?.str("displayName").orEmpty()
                    .ifEmpty { outStatuses[c.obj("status")?.obj("type")?.str("name").orEmpty()].orEmpty() },
                toPar = c.optJSONObject("score")?.str("displayValue").orEmpty(),
                total = c.optJSONObject("score")?.numText("value").orEmpty(),
                rounds = lines.map { it.numText("value") }.filter { it.isNotEmpty() },
            )
        }.sortedByDescending { it.date }
        return GolferOverview(
            seasonTitle = st?.str("displayName").orEmpty(),
            labels = st?.arr("labels").strings().orEmpty(),
            rows = st?.arr("splits").objects().orEmpty().map { it.str("displayName") to it.arr("stats").strings() },
            recent = recent,
            news = root.arr("news").objects().map { NewsItem(it.str("headline"), it.str("description"), it.str("published")) }
                .filter { it.headline.isNotBlank() },
        )
    }
}
