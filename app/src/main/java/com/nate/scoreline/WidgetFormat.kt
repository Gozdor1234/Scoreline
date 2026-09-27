package com.nate.scoreline

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One line of the home-screen widget, already formatted. */
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
)

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

    /** Same order as the app: favorites, then live, upcoming, final; then by start time. */
    fun rows(games: List<Game>, favIds: Set<String>, wide: Boolean, zone: ZoneId, today: LocalDate): List<WidgetRow> {
        val stateOrder = mapOf("in" to 0, "pre" to 1, "post" to 2)
        fun isFav(g: Game) = g.home.id in favIds || g.away.id in favIds
        return games
            .sortedWith(compareBy<Game>({ if (isFav(it)) 0 else 1 }, { stateOrder[it.state] ?: 3 }, { it.date }))
            .map { g ->
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
}
