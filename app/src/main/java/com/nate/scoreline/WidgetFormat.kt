package com.nate.scoreline

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One line of the home-screen widget, already formatted. A row with [header] set is a day divider. */
data class WidgetRow(
    val eventId: String,
    val awayName: String,
    val homeName: String,
    val awayLogo: String,
    val homeLogo: String,
    /** Score ("10 - 17") for live/final games, kickoff time for upcoming ones. */
    val center: String,
    /** Clock/quarter, "Final", or TV network. */
    val status: String,
    val live: Boolean,
    val final: Boolean,
    val awayBall: Boolean,
    val homeBall: Boolean,
    val header: String? = null,
) {
    companion object {
        fun divider(label: String) = WidgetRow("", "", "", "", "", "", "", false, false, false, false, header = label)
    }
}

/** Pure formatting for the widget (no Android types, so it's unit-tested). */
object WidgetFormat {
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE h:mm a")

    /** Abbreviation when the widget is narrow, nickname when wide; college rank prefixed. */
    fun name(t: TeamSide, wide: Boolean): String {
        val base = if (wide) t.shortName.ifEmpty { t.abbr } else t.abbr.ifEmpty { t.shortName }
        return if (t.rank != null) "${t.rank} $base" else base
    }

    /** "4:05 PM" for today's games, "Mon 8:15 PM" otherwise, in the phone's time zone. */
    fun kickoff(iso: String, zone: ZoneId, today: LocalDate): String = try {
        val local = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        if (local.toLocalDate() == today) local.format(timeFmt) else local.format(dayTimeFmt)
    } catch (e: Exception) {
        ""
    }

    /** "TODAY · WED, SEP 30", "TOMORROW · …", "YESTERDAY · …", else "THURSDAY, OCT 1". Shared with the app. */
    fun dayLabel(d: LocalDate?, today: LocalDate): String {
        if (d == null) return "DATE TBD"
        val short = d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        return when (d) {
            today -> "Today  ·  $short"
            today.plusDays(1) -> "Tomorrow  ·  $short"
            today.minusDays(1) -> "Yesterday  ·  $short"
            else -> d.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        }.uppercase()
    }

    /**
     * Same order as the app: favorites first, then the other games grouped by local day
     * (a divider row before each day), live games first within a day, then by start time.
     */
    fun rows(games: List<Game>, favIds: Set<String>, wide: Boolean, zone: ZoneId, today: LocalDate): List<WidgetRow> {
        val stateOrder = mapOf("in" to 0, "pre" to 1, "post" to 2)
        fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
        fun day(g: Game) = try { OffsetDateTime.parse(g.date).atZoneSameInstant(zone).toLocalDate() } catch (e: Exception) { null }
        val favs = games.filter(::isFav).sortedWith(compareBy<Game>({ stateOrder[it.state] ?: 3 }, { it.date }))
        val out = favs.map { row(it, wide, zone, today) }.toMutableList()
        games.filterNot(::isFav).groupBy(::day).toSortedMap(nullsLast(compareBy<LocalDate> { it })).forEach { (d, list) ->
            out += WidgetRow.divider(dayLabel(d, today))
            list.sortedWith(compareBy<Game>({ if (it.state == "in") 0 else 1 }, { it.date })).forEach { out += row(it, wide, zone, today) }
        }
        return out
    }

    private fun row(g: Game, wide: Boolean, zone: ZoneId, today: LocalDate): WidgetRow = run {
                val pre = g.state == "pre"
                WidgetRow(
                    eventId = g.id,
                    awayName = name(g.away, wide),
                    homeName = "@ " + name(g.home, wide),
                    awayLogo = g.away.logo,
                    homeLogo = g.home.logo,
                    center = if (pre) kickoff(g.date, zone, today).ifEmpty { g.detail } else "${g.away.score} - ${g.home.score}",
                    status = if (pre) g.broadcast else g.detail,
                    live = g.isLive,
                    final = g.state == "post",
                    awayBall = g.isLive && g.possessionTeamId != null && g.possessionTeamId == g.away.id,
                    homeBall = g.isLive && g.possessionTeamId != null && g.possessionTeamId == g.home.id,
                )
            }
}
