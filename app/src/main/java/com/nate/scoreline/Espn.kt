package com.nate.scoreline

import org.json.JSONObject

enum class League(val label: String, val path: String, val scoreboardParams: String, val regularWeeks: Int) {
    NFL("NFL", "football/nfl", "", 18),
    CFB("College", "football/college-football", "groups=80&limit=300", 16);
    // groups=80 = all FBS games; without it ESPN only returns a curated subset.
}

data class TeamSide(
    val id: String,
    val abbr: String,
    val name: String,
    val logo: String,
    val score: String,
    val record: String,
    val rank: Int?,
    val winner: Boolean,
    val homeAway: String,
    val linescores: List<String>,
)

data class Game(
    val id: String,
    val league: League,
    val date: String,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val away: TeamSide,
    val home: TeamSide,
    val possessionTeamId: String?,
    val downDistance: String,
    val broadcast: String,
) {
    val isLive get() = state == "in"
    val matchup get() = "${away.abbr} @ ${home.abbr}"
}

data class WeekInfo(val seasonType: Int, val week: Int) {
    val label: String
        get() = when (seasonType) {
            1 -> "Preseason wk $week"
            3 -> "Postseason wk $week"
            else -> "Week $week"
        }
}

data class Scoreboard(val games: List<Game>, val week: WeekInfo?)

data class StatLine(val label: String, val away: String, val home: String)

data class PlayerTable(
    val teamAbbr: String,
    val title: String,
    val labels: List<String>,
    val rows: List<Pair<String, List<String>>>,
    val totals: List<String>,
)

data class ScoringPlay(
    val period: Int,
    val clock: String,
    val team: String,
    val text: String,
    val awayScore: String,
    val homeScore: String,
)

data class GameDetail(
    val game: Game?,
    val teamStats: List<StatLine>,
    val players: List<PlayerTable>,
    val scoring: List<ScoringPlay>,
)

data class StandingRow(val teamId: String, val name: String, val abbr: String, val logo: String, val cols: List<String>)
data class StandingGroup(val title: String, val headers: List<String>, val rows: List<StandingRow>)

data class TeamRef(val league: League, val id: String, val name: String, val abbr: String, val logo: String)

object Espn {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val V2 = "https://site.api.espn.com/apis/v2/sports"

    fun scoreboardUrl(league: League, week: WeekInfo?): String {
        val params = mutableListOf<String>()
        if (league.scoreboardParams.isNotEmpty()) params += league.scoreboardParams
        if (week != null) {
            params += "seasontype=${week.seasonType}"
            params += "week=${week.week}"
        }
        return "$SITE/${league.path}/scoreboard" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    suspend fun scoreboard(league: League, week: WeekInfo? = null): Scoreboard =
        parseScoreboard(Net.getJson(scoreboardUrl(league, week)), league)

    suspend fun summary(league: League, eventId: String): GameDetail =
        parseSummary(Net.getJson("$SITE/${league.path}/summary?event=$eventId"), league)

    suspend fun standings(league: League): List<StandingGroup> {
        // level=3 asks for division-level groups (NFL); harmless where unsupported.
        val q = if (league == League.NFL) "?level=3" else ""
        return parseStandings(Net.getJson("$V2/${league.path}/standings$q"))
    }

    suspend fun teams(league: League): List<TeamRef> =
        parseTeams(Net.getJson("$SITE/${league.path}/teams?limit=1000"), league)

    // ---------- parsers (pure, unit-testable) ----------

    fun parseScoreboard(root: JSONObject, league: League): Scoreboard {
        val games = root.arr("events").objects().mapNotNull { parseEvent(it, league) }
        val seasonType = root.obj("season")?.optInt("type", 0) ?: 0
        val weekNum = root.obj("week")?.optInt("number", 0) ?: 0
        val week = if (seasonType > 0 && weekNum > 0) WeekInfo(seasonType, weekNum) else null
        return Scoreboard(games, week)
    }

    fun parseEvent(ev: JSONObject, league: League): Game? {
        val comp = ev.arr("competitions").objects().firstOrNull() ?: return null
        val statusType = (comp.obj("status") ?: ev.obj("status"))?.obj("type")
        val sides = comp.arr("competitors").objects().map { parseSide(it) }
        val home = sides.firstOrNull { it.homeAway == "home" } ?: sides.getOrNull(0) ?: return null
        val away = sides.firstOrNull { it.homeAway == "away" } ?: sides.getOrNull(1) ?: return null
        val situation = comp.obj("situation")
        val broadcast = comp.arr("broadcasts").objects()
            .flatMap { it.arr("names").strings() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
        return Game(
            id = ev.str("id").ifEmpty { comp.str("id") },
            league = league,
            date = ev.str("date").ifEmpty { comp.str("date") },
            state = statusType?.str("state")?.ifEmpty { null } ?: "pre",
            detail = statusType?.str("shortDetail")?.ifEmpty { null } ?: statusType?.str("detail") ?: "",
            away = away,
            home = home,
            possessionTeamId = situation?.str("possession")?.ifEmpty { null },
            downDistance = situation?.str("shortDownDistanceText")?.ifEmpty { null }
                ?: situation?.str("downDistanceText") ?: "",
            broadcast = broadcast,
        )
    }

    private fun parseSide(c: JSONObject): TeamSide {
        val t = c.obj("team") ?: JSONObject()
        val logo = t.str("logo").ifEmpty { t.arr("logos").objects().firstOrNull()?.str("href") ?: "" }
        // Scoreboard uses "records"; the summary header uses "record".
        val record = (c.arr("records") ?: c.arr("record")).objects()
            .firstOrNull()?.let { it.str("summary").ifEmpty { it.str("displayValue") } } ?: ""
        val rankRaw = c.obj("curatedRank")?.optInt("current", 99) ?: c.optInt("rank", 99)
        val lines = c.arr("linescores").objects().map { ls ->
            ls.str("displayValue").ifEmpty { ls.numText("value") }
        }
        val scoreObj = c.optJSONObject("score")
        val score = scoreObj?.str("displayValue") ?: c.numText("score")
        return TeamSide(
            id = t.str("id"),
            abbr = t.str("abbreviation").ifEmpty { t.str("shortDisplayName") },
            name = t.str("displayName").ifEmpty { t.str("name") },
            logo = logo,
            score = score,
            record = record,
            rank = rankRaw.takeIf { it in 1..25 },
            winner = c.optBoolean("winner", false),
            homeAway = c.str("homeAway"),
            linescores = lines,
        )
    }

    fun parseSummary(root: JSONObject, league: League): GameDetail {
        val header = root.obj("header")
        val game = header?.let { parseEvent(it, league) }
        val box = root.obj("boxscore")

        // Team stats: pair away/home by label.
        val boxTeams = box?.arr("teams").objects()
        val awayBox = boxTeams.firstOrNull { it.str("homeAway") == "away" } ?: boxTeams.getOrNull(0)
        val homeBox = boxTeams.firstOrNull { it.str("homeAway") == "home" } ?: boxTeams.getOrNull(1)
        fun statMap(o: JSONObject?): List<Pair<String, String>> = o?.arr("statistics").objects().map {
            val label = it.str("label").ifEmpty { it.str("name") }
            label to it.str("displayValue").ifEmpty { it.numText("value") }
        }
        val awayStats = statMap(awayBox)
        val homeStats = statMap(homeBox).toMap()
        val teamStats = awayStats.map { (label, v) -> StatLine(label, v, homeStats[label] ?: "") }

        // Player stat tables, one per team per category (passing, rushing, ...).
        val players = box?.arr("players").objects().flatMap { teamBlock ->
            val abbr = teamBlock.obj("team")?.str("abbreviation") ?: ""
            teamBlock.arr("statistics").objects().mapNotNull { cat ->
                val rows = cat.arr("athletes").objects().map { a ->
                    val ath = a.obj("athlete")
                    val name = ath?.str("displayName") ?: "?"
                    val jersey = ath?.str("jersey") ?: ""
                    (if (jersey.isNotEmpty()) "$name #$jersey" else name) to a.arr("stats").strings()
                }
                if (rows.isEmpty()) return@mapNotNull null
                PlayerTable(
                    teamAbbr = abbr,
                    title = cat.str("text").ifEmpty { cat.str("name").replaceFirstChar { it.uppercase() } },
                    labels = cat.arr("labels").strings(),
                    rows = rows,
                    totals = cat.arr("totals").strings(),
                )
            }
        }

        val scoring = root.arr("scoringPlays").objects().map { p ->
            val team = p.obj("team")
            ScoringPlay(
                period = p.obj("period")?.optInt("number", 0) ?: 0,
                clock = p.obj("clock")?.str("displayValue") ?: "",
                team = team?.str("abbreviation")?.ifEmpty { null } ?: team?.str("displayName") ?: "",
                text = p.str("text"),
                awayScore = p.numText("awayScore"),
                homeScore = p.numText("homeScore"),
            )
        }
        return GameDetail(game, teamStats, players, scoring)
    }

    fun parseStandings(root: JSONObject): List<StandingGroup> {
        val out = mutableListOf<StandingGroup>()
        fun walk(node: JSONObject) {
            val entries = node.obj("standings")?.arr("entries").objects()
            if (entries.isNotEmpty()) out += buildGroup(node.str("name"), entries)
            node.arr("children").objects().forEach { walk(it) }
        }
        walk(root)
        return out
    }

    private val standingHeaders = listOf("W-L", "Conf", "PF", "PA", "Strk")

    private fun buildGroup(title: String, entries: List<JSONObject>): StandingGroup {
        data class Tmp(val row: StandingRow, val seed: Int, val pct: Double)
        val tmps = entries.map { e ->
            val t = e.obj("team") ?: JSONObject()
            val stats = e.arr("stats").objects()
            fun byName(vararg names: String): JSONObject? =
                names.firstNotNullOfOrNull { n -> stats.firstOrNull { it.str("name") == n } }
            fun dv(vararg names: String): String = byName(*names)?.let {
                it.str("displayValue").ifEmpty { it.str("summary") }
            } ?: ""
            val overall = dv("overall").ifEmpty {
                val w = dv("wins"); val l = dv("losses"); val tie = dv("ties")
                if (w.isEmpty()) "" else if (tie.isEmpty() || tie == "0") "$w-$l" else "$w-$l-$tie"
            }
            val row = StandingRow(
                teamId = t.str("id"),
                name = t.str("displayName").ifEmpty { t.str("name") },
                abbr = t.str("abbreviation"),
                logo = t.arr("logos").objects().firstOrNull()?.str("href") ?: "",
                cols = listOf(overall, dv("vs. Conf.", "vsConf"), dv("pointsFor"), dv("pointsAgainst"), dv("streak")),
            )
            val seed = byName("playoffSeed")?.optDouble("value", 0.0)?.toInt() ?: 0
            val pct = byName("winPercent")?.optDouble("value", -1.0) ?: -1.0
            Tmp(row, seed, pct)
        }
        val sorted = if (tmps.all { it.seed > 0 }) tmps.sortedBy { it.seed }
        else if (tmps.any { it.pct >= 0 }) tmps.sortedByDescending { it.pct }
        else tmps
        // Drop columns that are empty for every team (e.g. college PF/PA).
        val rows = sorted.map { it.row }
        val keep = standingHeaders.indices.filter { i -> rows.any { it.cols[i].isNotBlank() } }
        return StandingGroup(
            title = title,
            headers = keep.map { standingHeaders[it] },
            rows = rows.map { r -> r.copy(cols = keep.map { r.cols[it] }) },
        )
    }

    fun parseTeams(root: JSONObject, league: League): List<TeamRef> =
        root.arr("sports").objects().flatMap { s ->
            s.arr("leagues").objects().flatMap { l ->
                l.arr("teams").objects().mapNotNull { wrap ->
                    val t = wrap.obj("team") ?: return@mapNotNull null
                    TeamRef(
                        league = league,
                        id = t.str("id"),
                        name = t.str("displayName"),
                        abbr = t.str("abbreviation"),
                        logo = t.arr("logos").objects().firstOrNull()?.str("href") ?: "",
                    )
                }
            }
        }.filter { it.id.isNotEmpty() }.sortedBy { it.name }
}
