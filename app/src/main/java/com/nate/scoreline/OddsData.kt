package com.nate.scoreline

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Pre-game betting lines for one game, from one sportsbook. Odds are American ("-110", "+136"). */
data class GameOdds(
    val source: String,
    val awayMl: String,
    val homeMl: String,
    /** e.g. "-2.5" / "+2.5" */
    val awaySpread: String,
    val awaySpreadOdds: String,
    val homeSpread: String,
    val homeSpreadOdds: String,
    /** e.g. "47.5" */
    val total: String,
    val overOdds: String,
    val underOdds: String,
) {
    val isEmpty get() = awayMl.isBlank() && homeMl.isBlank() && awaySpread.isBlank() && total.isBlank()
}

/** One event from The Odds API, reduced to what's needed to match and show it. */
data class OddsEvent(val homeTeam: String, val awayTeam: String, val commence: String, val odds: GameOdds)

object OddsParse {
    fun american(v: Double): String {
        val i = Math.round(v).toInt()
        return if (i > 0) "+$i" else "$i"
    }

    fun point(v: Double): String {
        val s = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        return if (v > 0) "+$s" else s
    }

    /** Opposite sign of a spread line: "-2.5" -> "+2.5", "PK"/"0" stays. */
    fun flip(line: String): String {
        val t = line.trim()
        return when {
            t.startsWith("-") -> "+" + t.drop(1)
            t.startsWith("+") -> "-" + t.drop(1)
            t.isEmpty() || t == "0" || t.equals("PK", true) -> t
            else -> "-$t"
        }
    }

    // ------------------------------------------------------------ ESPN (DraftKings)

    /** Newest price available: current, then close, then open. */
    private fun latest(o: JSONObject?): JSONObject? =
        o?.obj("current") ?: o?.obj("close") ?: o?.obj("open")

    /**
     * ESPN's odds object (scoreboard competitions[].odds[0] or summary pickcenter[0]).
     * Newer shape has moneyline/pointSpread/total blocks; older shape has awayTeamOdds.moneyLine,
     * overUnder, and a "details" string like "PIT -2.5".
     */
    fun espn(o: JSONObject, awayAbbr: String, homeAbbr: String): GameOdds? {
        val source = o.obj("provider")?.str("name").orEmpty().ifEmpty { "ESPN" }
        val ml = o.obj("moneyline")
        var awayMl = latest(ml?.obj("away"))?.str("odds").orEmpty()
        var homeMl = latest(ml?.obj("home"))?.str("odds").orEmpty()
        if (awayMl.isEmpty()) awayMl = o.obj("awayTeamOdds")?.let { if (it.has("moneyLine")) american(it.optDouble("moneyLine", Double.NaN)) else "" }.orEmpty()
        if (homeMl.isEmpty()) homeMl = o.obj("homeTeamOdds")?.let { if (it.has("moneyLine")) american(it.optDouble("moneyLine", Double.NaN)) else "" }.orEmpty()

        val ps = o.obj("pointSpread")
        var awaySp = latest(ps?.obj("away"))?.str("line").orEmpty()
        var homeSp = latest(ps?.obj("home"))?.str("line").orEmpty()
        val awaySpOdds = latest(ps?.obj("away"))?.str("odds").orEmpty()
            .ifEmpty { o.obj("awayTeamOdds")?.let { if (it.has("spreadOdds")) american(it.optDouble("spreadOdds", Double.NaN)) else "" }.orEmpty() }
        val homeSpOdds = latest(ps?.obj("home"))?.str("odds").orEmpty()
            .ifEmpty { o.obj("homeTeamOdds")?.let { if (it.has("spreadOdds")) american(it.optDouble("spreadOdds", Double.NaN)) else "" }.orEmpty() }
        if (awaySp.isEmpty() && homeSp.isEmpty()) {
            // "PIT -2.5": the named team gets that line, the other team the opposite.
            val m = Regex("""^([A-Za-z&.' ]+?)\s+([+-]?\d+(\.\d+)?)$""").find(o.str("details").trim())
            if (m != null) {
                val team = m.groupValues[1].trim()
                val line = m.groupValues[2].let { if (it.startsWith("-") || it.startsWith("+")) it else "+$it" }
                when {
                    team.equals(awayAbbr, true) -> { awaySp = line; homeSp = flip(line) }
                    team.equals(homeAbbr, true) -> { homeSp = line; awaySp = flip(line) }
                }
            }
        }

        val tot = o.obj("total")
        var total = latest(tot?.obj("over"))?.str("line").orEmpty().trimStart('o', 'O', 'u', 'U')
        if (total.isEmpty() && o.has("overUnder")) total = o.numText("overUnder")
        val overOdds = latest(tot?.obj("over"))?.str("odds").orEmpty()
            .ifEmpty { if (o.has("overOdds")) american(o.optDouble("overOdds", Double.NaN)) else "" }
        val underOdds = latest(tot?.obj("under"))?.str("odds").orEmpty()
            .ifEmpty { if (o.has("underOdds")) american(o.optDouble("underOdds", Double.NaN)) else "" }

        return GameOdds(source, awayMl, homeMl, awaySp, awaySpOdds, homeSp, homeSpOdds, total, overOdds, underOdds)
            .takeUnless { it.isEmpty }
    }

    // ------------------------------------------------------------ The Odds API (FanDuel)

    fun oddsApi(root: JSONArray, bookmaker: String = "fanduel"): List<OddsEvent> =
        (0 until root.length()).mapNotNull { i ->
            val ev = root.optJSONObject(i) ?: return@mapNotNull null
            val home = ev.str("home_team")
            val away = ev.str("away_team")
            val book = ev.arr("bookmakers").objects().firstOrNull { it.str("key") == bookmaker } ?: return@mapNotNull null
            fun market(k: String) = book.arr("markets").objects().firstOrNull { it.str("key") == k }?.arr("outcomes").objects().orEmpty()
            fun outcome(list: List<JSONObject>, name: String) = list.firstOrNull { it.str("name").equals(name, true) }
            fun price(o: JSONObject?) = o?.let { if (it.has("price")) american(it.optDouble("price", Double.NaN)) else "" }.orEmpty()
            fun pt(o: JSONObject?) = o?.let { if (it.has("point")) point(it.optDouble("point", Double.NaN)) else "" }.orEmpty()
            val h2h = market("h2h")
            val spreads = market("spreads")
            val totals = market("totals")
            val over = outcome(totals, "Over")
            OddsEvent(
                homeTeam = home,
                awayTeam = away,
                commence = ev.str("commence_time"),
                odds = GameOdds(
                    source = book.str("title").ifEmpty { "FanDuel" },
                    awayMl = price(outcome(h2h, away)),
                    homeMl = price(outcome(h2h, home)),
                    awaySpread = pt(outcome(spreads, away)),
                    awaySpreadOdds = price(outcome(spreads, away)),
                    homeSpread = pt(outcome(spreads, home)),
                    homeSpreadOdds = price(outcome(spreads, home)),
                    total = over?.let { if (it.has("point")) it.numText("point") else "" }.orEmpty(),
                    overOdds = price(over),
                    underOdds = price(outcome(totals, "Under")),
                ),
            )
        }.filterNot { it.odds.isEmpty }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace("&", "and").replace(Regex("[^a-z0-9]"), "")

    /**
     * Finds the Odds API event for an ESPN game: both full team names match; otherwise same
     * kickoff (within 12 hours) and one name matching exactly or by nickname.
     */
    fun match(events: List<OddsEvent>, g: Game): OddsEvent? {
        val a = norm(g.away.name)
        val h = norm(g.home.name)
        events.firstOrNull { norm(it.awayTeam) == a && norm(it.homeTeam) == h }?.let { return it }
        events.firstOrNull { norm(it.awayTeam) == h && norm(it.homeTeam) == a }?.let { return it } // neutral site listed the other way
        val kick = Golf.instant(g.date) ?: return null
        fun nick(full: String, short: String) = norm(short.ifEmpty { full.substringAfterLast(' ') })
        val an = nick(g.away.name, g.away.shortName)
        val hn = nick(g.home.name, g.home.shortName)
        return events.firstOrNull { e ->
            val t = Golf.instant(e.commence) ?: return@firstOrNull false
            Math.abs(t.epochSecond - kick.epochSecond) <= 12 * 3600 &&
                (norm(e.homeTeam) == h || norm(e.awayTeam) == a ||
                    (hn.isNotEmpty() && norm(e.homeTeam).endsWith(hn) && an.isNotEmpty() && norm(e.awayTeam).endsWith(an)))
        }
    }
}
