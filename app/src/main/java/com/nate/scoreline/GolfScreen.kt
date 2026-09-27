@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val GOLF_LIVE_MS = 60_000L

// ---------------------------------------------------------------- Golf tab

@Composable
fun GolfScreen(modifier: Modifier, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nowP = rememberPolled<GolfNow>("golf-now", { n -> if (n?.state == "in") GOLF_LIVE_MS else IDLE_MS }) { Golf.now() }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Leaderboard", "Schedule").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        LoadableContent(nowP) { now ->
            if (tab == 0) GolfHome(now, open) else GolfSchedule(now, open)
        }
    }
}

@Composable
private fun GolfHome(now: GolfNow, open: (Route) -> Unit) {
    // Team events (Ryder/Presidents Cup) have no individual leaderboard: show the team score,
    // then the most recent stroke-play tournament underneath.
    val (_, done) = Golf.splitCalendar(now.calendar, Instant.now())
    val lbId = if (!now.teamEvent) now.eventId.ifEmpty { null } else done.firstOrNull { it.id != now.eventId }?.id
    LeaderboardList(
        eventId = lbId,
        open = open,
        showHeader = true,
        before = if (now.teamEvent) {
            {
                item { TeamEventCard(now) }
                item { ListTitle("Most recent tournament") }
            }
        } else null,
    )
}

@Composable
private fun TeamEventCard(now: GolfNow) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(now.eventName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (now.state == "in") LiveBadge()
            }
            if (now.statusDetail.isNotBlank()) {
                Text(now.statusDetail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            now.teams.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(t.logo, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(t.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(t.score, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Team match play: individual matches aren't shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ListTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** Loads one tournament's leaderboard (null id = ESPN's default) and lists it. */
@Composable
private fun LeaderboardList(
    eventId: String?,
    open: (Route) -> Unit,
    showHeader: Boolean,
    before: (LazyListScope.() -> Unit)? = null,
) {
    val p = rememberPolled<GolfTournament?>("golf-lb-${eventId ?: "default"}", { t -> if (t?.state == "in") GOLF_LIVE_MS else IDLE_MS }) {
        Golf.tournament(eventId)
    }
    var view by rememberSaveable(eventId) { mutableIntStateOf(0) } // 0 = live/final, n = after round n
    val s = p.state
    if (s.data == null && !s.loading && s.error == null) {
        LazyColumn {
            before?.invoke(this)
            item { InlineNote("No leaderboard for this event yet.") }
        }
        return
    }
    LoadableContent(p) { t ->
        if (t == null) return@LoadableContent
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            before?.invoke(this)
            if (showHeader) item { TournamentHeader(t) { open(Route.GolfEvent(t.id)) } }
            leaderboardItems(t, open, view, onView = { view = it })
        }
    }
}

/**
 * Leaderboard rows with the round picker. [limit] shows only the top N with a "Show all" /
 * "Show top N" toggle (used on the Info tab); null shows everyone.
 */
private fun LazyListScope.leaderboardItems(
    t: GolfTournament,
    open: (Route) -> Unit,
    view: Int,
    onView: (Int) -> Unit,
    limit: Int? = null,
    expanded: Boolean = true,
    onExpand: (Boolean) -> Unit = {},
) {
    if (t.entries.isEmpty()) {
        item { InlineNote(if (t.state == "pre") "The field and tee times haven't been posted yet." else "No leaderboard yet.") }
        return
    }
    // "Live" (or "Final") plus one chip per finished round: standings as they stood at the end of that day.
    val done = Golf.completedRounds(t).let { r -> if (t.state == "post") r.dropLast(1) else r }
    if (done.isNotEmpty()) {
        item(key = "round-chips") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = view == 0, onClick = { onView(0) }, label = { Text(if (t.state == "post") "Final" else "Live") })
                done.forEach { r -> FilterChip(selected = view == r, onClick = { onView(r) }, label = { Text("After R$r") }) }
            }
        }
    }
    if (view > 0 && view in done) {
        val rows = Golf.standingsAfter(t.entries, view)
        val shown = if (limit != null && !expanded) rows.take(limit) else rows
        item(key = "round-head") { RoundHeaderRow(view) }
        shown.forEachIndexed { i, r ->
            item(key = "r$view-${r.entry.athleteId}-$i") {
                RoundStandingRow(r) { if (r.entry.athleteId.isNotEmpty()) open(Route.Golfer(r.entry.athleteId)) }
            }
        }
        showAllToggle(limit, rows.size, expanded, onExpand)
        return
    }
    val shown = if (limit != null && !expanded) t.entries.take(limit) else t.entries
    item(key = "lb-head") { LeaderboardHeaderRow() }
    val firstOut = shown.indexOfFirst { it.outStatus.isNotEmpty() }
    shown.forEachIndexed { i, e ->
        if (i == firstOut) item(key = "cutline") { ListTitle("Missed cut / withdrawn") }
        item(key = "g-${e.athleteId}-$i") {
            GolferRow(e) { if (e.athleteId.isNotEmpty()) open(Route.Golfer(e.athleteId)) }
        }
    }
    showAllToggle(limit, t.entries.size, expanded, onExpand)
}

private fun LazyListScope.showAllToggle(limit: Int?, total: Int, expanded: Boolean, onExpand: (Boolean) -> Unit) {
    if (limit == null || total <= limit) return
    item(key = "lb-toggle") {
        TextButton(onClick = { onExpand(!expanded) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (expanded) "Show top $limit" else "Show all $total players")
        }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("MMM d")

/** "Aug 27 – 30" or "Aug 30 – Sep 2" in the phone's time zone. */
fun golfDates(start: String, end: String): String {
    val z = ZoneId.systemDefault()
    val s = Golf.instant(start)?.atZone(z) ?: return ""
    val e = Golf.instant(end)?.atZone(z)
    return when {
        e == null || e.toLocalDate() == s.toLocalDate() -> s.format(dayFmt)
        e.month == s.month -> "${s.format(dayFmt)} – ${e.dayOfMonth}"
        else -> "${s.format(dayFmt)} – ${e.format(dayFmt)}"
    }
}

@Composable
private fun TournamentHeader(t: GolfTournament, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    if (t.state == "in") { Spacer(Modifier.width(8.dp)); LiveBadge() }
                }
                Text(
                    listOf(t.statusDetail, golfDates(t.start, t.end)).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Tournament info", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun LeaderboardHeaderRow() {
    val st = MaterialTheme.typography.labelSmall
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("POS", Modifier.width(42.dp), style = st, color = c)
        Text("PLAYER", Modifier.weight(1f), style = st, color = c)
        Text("TO PAR", Modifier.width(54.dp), textAlign = TextAlign.End, style = st, color = c)
        Text("TODAY", Modifier.width(50.dp), textAlign = TextAlign.End, style = st, color = c)
        Text("THRU", Modifier.width(62.dp), textAlign = TextAlign.End, style = st, color = c)
    }
}

@Composable
private fun RoundHeaderRow(round: Int) {
    val st = MaterialTheme.typography.labelSmall
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("POS", Modifier.width(42.dp), style = st, color = c)
        Text("PLAYER", Modifier.weight(1f), style = st, color = c)
        Text("TOTAL", Modifier.width(54.dp), textAlign = TextAlign.End, style = st, color = c)
        Text("R$round", Modifier.width(50.dp), textAlign = TextAlign.End, style = st, color = c)
        Text("STROKES", Modifier.width(62.dp), textAlign = TextAlign.End, style = st, color = c)
    }
}

@Composable
private fun RoundStandingRow(r: RoundStanding, onClick: () -> Unit) {
    val total = Golf.toParText(r.total)
    val rnd = Golf.toParText(r.roundToPar)
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.position, Modifier.width(42.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (r.entry.flag.isNotBlank()) { Logo(r.entry.flag, 16.dp); Spacer(Modifier.width(6.dp)) }
                Text(r.entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(total, Modifier.width(54.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = parColor(total))
            Text(rnd, Modifier.width(50.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, color = parColor(rnd))
            Text(r.roundStrokes.toString(), Modifier.width(62.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

/** Under par shows in the live color (red), like TV leaderboards. */
@Composable
private fun parColor(score: String) =
    if (score.startsWith("-")) LiveRed else MaterialTheme.colorScheme.onSurface

@Composable
private fun GolferRow(e: GolfEntry, onClick: () -> Unit) {
    val out = e.outStatus.isNotEmpty()
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(e.position, Modifier.width(42.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (out) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.flag.isNotBlank()) { Logo(e.flag, 16.dp); Spacer(Modifier.width(6.dp)) }
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (out) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                if (e.rounds.isNotEmpty()) {
                    Text(
                        e.rounds.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(e.toPar, Modifier.width(54.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold, color = if (out) MaterialTheme.colorScheme.onSurfaceVariant else parColor(e.toPar))
            Text(e.today, Modifier.width(50.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, color = parColor(e.today))
            Text(e.thru, Modifier.width(62.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        HorizontalDivider()
    }
}

@Composable
private fun GolfSchedule(now: GolfNow, open: (Route) -> Unit) {
    val (upcoming, done) = Golf.splitCalendar(now.calendar, Instant.now())
    if (now.calendar.isEmpty()) {
        Message("No schedule published.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (upcoming.isNotEmpty()) {
            item { ListTitle("This week & upcoming") }
            items(upcoming, key = { "u-${it.id}" }) { c -> ScheduleRow(c, live = c.id == now.eventId && now.state == "in") { open(Route.GolfEvent(c.id)) } }
        }
        if (done.isNotEmpty()) {
            item { ListTitle("Completed") }
            items(done, key = { "d-${it.id}" }) { c -> ScheduleRow(c, live = false) { open(Route.GolfEvent(c.id)) } }
        }
    }
}

@Composable
private fun ScheduleRow(c: GolfCalendarItem, live: Boolean, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal)
                Text(golfDates(c.start, c.end), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live) LiveBadge()
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

// ---------------------------------------------------------------- tournament page

@Composable
fun GolfEventScreen(eventId: String, onBack: () -> Unit, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var infoExpanded by rememberSaveable { mutableStateOf(false) }
    var scRound by rememberSaveable { mutableStateOf<Int?>(null) } // null = all rounds
    // Hole-by-hole scores only load when the Info tab is open.
    val scP = if (tab == 0) {
        rememberPolled<CourseScorecard>("golf-sc-$eventId", { 10 * 60_000L }) { Golf.scorecard(eventId) }
    } else null
    val p = rememberPolled<GolfTournament?>("golf-ev-$eventId", { t -> if (t?.state == "in") GOLF_LIVE_MS else IDLE_MS }) {
        Golf.tournament(eventId)
    }
    val t = p.state.data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.name ?: "Tournament", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Info", "Leaderboard").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
            }
            val st = p.state
            when {
                st.data == null && !st.loading && st.error == null ->
                    Message("No details for this event. Team events like the Ryder Cup and Presidents Cup don't have an individual leaderboard.")
                else -> LoadableContent(p) { tour ->
                    if (tour == null) return@LoadableContent
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (tab == 0) {
                            item { TournamentInfo(tour) }
                            // Leaderboard (same round picker as the Leaderboard tab), top 10 with "Show all".
                            if (tour.entries.isNotEmpty()) {
                                item(key = "info-lb-title") {
                                    Text(
                                        "Leaderboard",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                                    )
                                }
                            }
                            leaderboardItems(tour, open, view, { view = it }, limit = 10, expanded = infoExpanded, onExpand = { infoExpanded = it })
                            scorecardItems(scP, scRound) { scRound = it }
                        } else {
                            leaderboardItems(tour, open, view, onView = { view = it })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TournamentInfo(t: GolfTournament) {
    val leader = t.entries.firstOrNull { it.outStatus.isEmpty() }
    val rows = listOf(
        "Status" to t.statusDetail,
        "Dates" to golfDates(t.start, t.end),
        "Round" to if (t.currentRound > 0) "${t.currentRound} of ${t.rounds}" else "${t.rounds} rounds",
        "Purse" to t.purse,
        "Major" to if (t.major) "Yes" else "",
        "Defending champion" to t.defendingChampion,
        (if (t.state == "post") "Winner" else "Leader") to (leader?.let { "${it.name} (${it.toPar})" } ?: ""),
        "Cut line" to if (t.cutRound > 0 && t.cutScore.isNotBlank()) "${t.cutScore} after round ${t.cutRound}" else "",
        "Made the cut" to if (t.cutCount > 0) "${t.cutCount} players" else "",
        "Field" to if (t.entries.isNotEmpty()) "${t.entries.size} players" else "",
    ).filter { it.second.isNotBlank() }
    Column {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.background(teamFade(null)).padding(vertical = 4.dp)) {
                rows.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(k, Modifier.width(140.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    if (i < rows.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- golfer page

@Composable
fun GolferScreen(athleteId: String, onBack: () -> Unit, open: (Route) -> Unit) {
    val gP = rememberPolled<Golfer>("golfer-$athleteId", { 30 * 60_000L }) { Golf.golfer(athleteId) }
    val oP = rememberPolled<GolferOverview>("golfer-ov-$athleteId", { 15 * 60_000L }) { Golf.golferOverview(athleteId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(gP.state.data?.name ?: "Golfer") },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LoadableContent(gP) { g ->
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { GolferHeader(g) }
                    if (g.summary.isNotEmpty()) item { GolferStrip(g) }
                    item {
                        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                            listOf("Stats", "Results", "Bio").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
                        }
                    }
                    val ov = oP.state.data
                    when (tab) {
                        0 -> when {
                            ov == null && oP.state.error != null -> item { InlineNote("Couldn't load stats.") }
                            ov == null -> item { InlineLoading() }
                            ov.rows.isEmpty() -> item { InlineNote("No stats this season yet.") }
                            else -> item {
                                StatGrid(
                                    title = ov.seasonTitle,
                                    firstHeader = "",
                                    labels = ov.labels,
                                    rows = ov.rows,
                                    firstWidth = 96.dp,
                                    cellWidth = 64.dp,
                                )
                            }
                        }
                        1 -> when {
                            ov == null && oP.state.error != null -> item { InlineNote("Couldn't load results.") }
                            ov == null -> item { InlineLoading() }
                            ov.recent.isEmpty() -> item { InlineNote("No recent tournaments.") }
                            else -> items(ov.recent, key = { "r-${it.eventId}-${it.date}" }) { r ->
                                ResultRow(r) { if (r.eventId.isNotEmpty()) open(Route.GolfEvent(r.eventId)) }
                            }
                        }
                        else -> item { GolferBio(g) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GolferHeader(g: Golfer) {
    Row(Modifier.fillMaxWidth().background(teamFade(null)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Headshot(g.headshot.ifEmpty { "https://a.espncdn.com/i/headshots/golf/players/full/${g.id}.png" }, 96.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(g.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (g.flag.isNotBlank()) { Logo(g.flag, 18.dp); Spacer(Modifier.width(6.dp)) }
                Text(g.country, style = MaterialTheme.typography.bodyMedium)
            }
            val line = listOf(
                if (g.age.isNotBlank()) "Age ${g.age}" else "",
                if (g.turnedPro.isNotBlank()) "Pro since ${g.turnedPro}" else "",
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GolferStrip(g: Golfer) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors()) {
        Column {
            Text(
                g.summaryTitle.ifBlank { "Season" }.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(vertical = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                g.summary.take(4).forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(s.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (s.rank.isNotBlank()) Text(s.rank, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: GolferResult, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val sub = listOf(
                    golfDates(r.date, r.date),
                    r.rounds.joinToString("-") + if (r.total.isNotBlank()) " (${r.total})" else "",
                ).filter { it.isNotBlank() }.joinToString("  ·  ")
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (r.position.isNotBlank()) r.position else "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(r.toPar, style = MaterialTheme.typography.labelMedium, color = parColor(r.toPar))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun GolferBio(g: Golfer) {
    val rows = listOf(
        "Country" to g.country,
        "Age" to g.age,
        "Birthdate" to g.birthDate,
        "Birthplace" to g.birthPlace,
        "College" to g.college,
        "Turned pro" to g.turnedPro,
        "Height" to g.height,
        "Weight" to g.weight,
        "Plays" to if (g.hand.isNotBlank()) "${g.hand}-handed" else "",
    ).filter { it.second.isNotBlank() }
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                if (i < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------- hole-by-hole scorecard

/** Diverging colors (validated for light/dark and colorblind separation): red = under par, blue = over par. */
private data class ParColors(val under: Color, val even: Color, val over: Color)

@Composable
private fun parColors(): ParColors =
    if (ColorMath.isDark(MaterialTheme.colorScheme.background.toArgb())) {
        ParColors(Color(0xFFE66767), Color(0xFF383835), Color(0xFF3987E5))
    } else {
        ParColors(Color(0xFFE34948), Color(0xFFF0EFEC), Color(0xFF2A78D6))
    }

private fun vsParText(d: Double): String = when {
    Math.abs(d) < 0.005 -> "E"
    d > 0 -> "+" + "%.2f".format(d)
    else -> "%.2f".format(d)
}

private fun LazyListScope.scorecardItems(p: Polled<CourseScorecard>?, round: Int?, onRound: (Int?) -> Unit) {
    item(key = "sc-title") {
        Text(
            "Hole by hole",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
    }
    val sc = p?.state?.data
    when {
        p == null -> return
        sc == null && p.state.error != null -> { item(key = "sc-err") { InlineNote("Couldn't load hole-by-hole scores.") }; return }
        sc == null -> { item(key = "sc-load") { InlineLoading() }; return }
        sc.scores.isEmpty() -> { item(key = "sc-empty") { InlineNote("Hole-by-hole scores appear once play begins.") }; return }
    }
    val card = sc!!
    val holes = card.holes(round?.takeIf { it in card.rounds })
    if (card.rounds.size > 1) {
        item(key = "sc-rounds") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = round == null, onClick = { onRound(null) }, label = { Text("All rounds") })
                card.rounds.forEach { r -> FilterChip(selected = round == r, onClick = { onRound(r) }, label = { Text("R$r") }) }
            }
        }
    }
    item(key = "sc-summary") { ScorecardSummary(holes) }
    item(key = "sc-legend") { ScorecardLegend() }
    holes.forEach { h ->
        if (h.hole == 1 || h.hole == 10) {
            item(key = "sc-nine-${h.hole}") {
                val nine = holes.filter { if (h.hole == 1) it.hole <= 9 else it.hole >= 10 }
                Text(
                    (if (h.hole == 1) "Front nine" else "Back nine") + "  ·  Par ${nine.sumOf { it.par }}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
            }
        }
        item(key = "sc-h-${h.hole}") { HoleRow(h) }
    }
    item(key = "sc-note") {
        InlineNote(
            "Built from every player's hole-by-hole scores. ESPN doesn't publish course maps, hole yardages, or pin positions; " +
                "a hole that plays much harder in one round than another often reflects a tougher pin that day.",
        )
    }
}

@Composable
private fun ScorecardSummary(holes: List<HoleStat>) {
    if (holes.isEmpty()) return
    val hardest = holes.maxByOrNull { it.vsPar }!!
    val easiest = holes.minByOrNull { it.vsPar }!!
    val field = holes.sumOf { it.average } - holes.sumOf { it.par }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Par ${holes.sumOf { it.par }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SummaryLine("Field scoring average", vsParText(field) + " per round")
            SummaryLine("Hardest hole", "#${hardest.hole} (par ${hardest.par}, ${vsParText(hardest.vsPar)})")
            SummaryLine("Easiest hole", "#${easiest.hole} (par ${easiest.par}, ${vsParText(easiest.vsPar)})")
        }
    }
}

@Composable
private fun SummaryLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScorecardLegend() {
    val c = parColors()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("Birdie or better" to c.under, "Par" to c.even, "Bogey or worse" to c.over).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Hole #, par, field average and vs-par, and a bar of how the field scored. Tap for the exact counts. */
@Composable
private fun HoleRow(h: HoleStat) {
    var open by rememberSaveable(h.hole) { mutableStateOf(false) }
    val c = parColors()
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${h.hole}", Modifier.width(26.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("Par ${h.par}", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.2f".format(h.average), Modifier.width(42.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
            Text(vsParText(h.vsPar), Modifier.width(50.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            val segments = listOf(h.eagleOrBetter + h.birdies to c.under, h.pars to c.even, h.bogeys + h.doublePlus to c.over)
                .filter { it.first > 0 }
            Row(Modifier.weight(1f).height(12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                segments.forEachIndexed { i, (n, color) ->
                    val shape = RoundedCornerShape(
                        topStart = if (i == 0) 4.dp else 0.dp, bottomStart = if (i == 0) 4.dp else 0.dp,
                        topEnd = if (i == segments.lastIndex) 4.dp else 0.dp, bottomEnd = if (i == segments.lastIndex) 4.dp else 0.dp,
                    )
                    Box(Modifier.weight(n.toFloat()).fillMaxHeight().clip(shape).background(color))
                }
            }
        }
        if (open) {
            Text(
                "Eagles ${h.eagleOrBetter}  ·  Birdies ${h.birdies}  ·  Pars ${h.pars}  ·  Bogeys ${h.bogeys}  ·  Double+ ${h.doublePlus}  ·  ${h.count} scores",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp, top = 4.dp),
            )
        }
    }
}
