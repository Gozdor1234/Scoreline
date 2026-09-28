@file:OptIn(ExperimentalMaterial3Api::class)

package com.nate.scoreline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background

@Composable
fun F1Screen(modifier: Modifier, open: (Route) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("This weekend", "Season", "Last race").forEachIndexed { i, s ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) })
            }
        }
        when (tab) {
            0 -> WeekendView()
            1 -> SeasonView(open)
            else -> LastRaceView()
        }
    }
}

@Composable
private fun WeekendView() {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<List<F1Weekend>>(
        key = "f1w",
        intervalMs = { w -> if (w?.any { e -> e.sessions.any { it.isLive } } == true) LIVE_MS else IDLE_MS },
    ) { F1.weekends() }

    // Tyre stints (OpenF1) and points (Jolpica), refreshed alongside the weekend data.
    var extras by remember { mutableStateOf<Map<String, SessionExtra>>(emptyMap()) }
    LaunchedEffect(polled.state.updatedAt) {
        val ws = polled.state.data ?: return@LaunchedEffect
        val m = HashMap<String, SessionExtra>()
        ws.forEach { w -> m.putAll(runCatching { F1Extras.load(w) }.getOrDefault(emptyMap())) }
        extras = m
    }

    LoadableContent(polled) { weekends ->
        if (weekends.isEmpty()) {
            Message("No race weekend listed right now.", action = "Refresh", onAction = polled.refresh)
            return@LoadableContent
        }
        LazyColumn {
            weekends.forEach { w ->
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                listOf(w.circuit, w.location).filter { it.isNotBlank() }.joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = polled.refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                }
                item(key = "circuit-${w.id}") { CircuitCard(w) }
                // Newest session first: Race, then Qualifying, then practice.
                items(w.sessions.sortedByDescending { it.date }, key = { "${w.id}-${it.id}" }) { s -> SessionCard(s, fav, extras[s.id]) }
            }
            item {
                Text(
                    agoText(polled.state.updatedAt) + "  •  Live order from ESPN (running order, not official timing). Tyres from OpenF1 after each session; points from Jolpica.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionCard(s: F1Session, fav: Favorites, extra: SessionExtra?) {
    var expanded by remember(s.id) { mutableStateOf(s.isLive) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                if (s.isLive) LiveBadge()
                Spacer(Modifier.weight(1f))
                Text(
                    if (s.state == "pre") formatLocal(s.date).ifEmpty { s.detail } else s.detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val shown = if (expanded) s.results else s.results.take(3)
            shown.forEach { e ->
                val isFav = fav.isFavDriver(e.driver)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("P${e.pos}", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Logo(e.flag, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.driver + if (isFav) "  ♥" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isFav || e.winner) FontWeight.Bold else FontWeight.Normal,
                        color = if (isFav) LiveRed else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val key = F1.driverKey(e.driver)
                    extra?.tyres?.get(key)?.let { TyreStrip(it) }
                    extra?.points?.let { pts ->
                        val p = pts[key] ?: "0"
                        Text(
                            p,
                            Modifier.width(34.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (p != "0") FontWeight.SemiBold else FontWeight.Normal,
                            color = if (p != "0") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (s.results.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show top 3" else "Show all ${s.results.size}")
                }
            }
        }
    }
}

@Composable
private fun LastRaceView() {
    val fav = Favorites.get(LocalContext.current)
    val polled = rememberPolled<F1Race?>("f1last", { IDLE_MS * 2 }) { F1.lastRace() }
    val s = polled.state
    // F1Race? as T: a successful load that returns null means "no race yet this season".
    if (s.data == null && !s.loading && s.error == null) {
        Message("No completed race yet this season.")
        return
    }
    LoadableContent(polled) { race ->
        if (race == null) return@LoadableContent
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(race.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Round ${race.round}  •  ${race.circuit}  •  ${race.date}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            resultItems(race, fav)
            item { SourceNote() }
        }
    }
}

/** Loads the bundled circuit dataset once (off the main thread) and keeps it for the app's lifetime. */
object CircuitStore {
    @Volatile private var cache: List<Circuit>? = null
    fun all(ctx: android.content.Context): List<Circuit> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val text = ctx.assets.open("f1-circuits.geojson").bufferedReader().use { it.readText() }
            Circuits.parse(org.json.JSONObject(text))
        }.getOrDefault(emptyList()).also { cache = it }
    }
}

/** Track map plus circuit facts, under the race title. Hidden if the circuit isn't in the dataset. */
@Composable
private fun CircuitCard(w: F1Weekend) {
    val ctx = LocalContext.current
    val all by produceState<List<Circuit>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { CircuitStore.all(ctx) }
    }
    val list = all ?: return
    val city = w.location.substringBefore(",").trim()
    val c = remember(list, w.circuit, city) { Circuits.find(list, w.circuit, city) } ?: return
    val projected = remember(c.id) { Circuits.project(c.points) }
    val line = MaterialTheme.colorScheme.onSurface
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(bottom = 10.dp)) {
            Canvas(Modifier.fillMaxWidth().height(200.dp).padding(18.dp)) {
                val (pts, _) = projected
                val wU = pts.maxOf { it.first }.coerceAtLeast(1e-6f)
                val hU = pts.maxOf { it.second }.coerceAtLeast(1e-6f)
                val scale = minOf(size.width / wU, size.height / hU)
                val ox = (size.width - wU * scale) / 2f
                val oy = (size.height - hU * scale) / 2f
                val path = Path()
                pts.forEachIndexed { i, (x, y) ->
                    val px = ox + x * scale
                    val py = oy + y * scale
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, glow, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                c.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val facts = listOf(
                "Length" to if (c.lengthM > 0) "%.3f km".format(c.lengthM / 1000.0) else "",
                "First Grand Prix" to if (c.firstGp > 0) c.firstGp.toString() else "",
                "Location" to w.location.ifBlank { c.location },
                "Opened" to if (c.opened > 0) c.opened.toString() else "",
                "Altitude" to "${c.altitudeM} m",
            ).filter { it.second.isNotBlank() }
            // Two columns of facts
            val half = (facts.size + 1) / 2
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                listOf(facts.take(half), facts.drop(half)).forEach { col ->
                    Column(Modifier.weight(1f)) {
                        col.forEach { (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Text(
                "Track map: f1-circuits by Tomislav Bacinger (MIT license)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Compound letters in fitting order. On light backgrounds, white (hard) and yellow (medium)
 * get a faint dark halo so they stay readable; no box behind them.
 */
@Composable
private fun TyreStrip(tyres: List<Tyre>) {
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        tyres.forEach { t ->
            val halo = light && (t == Tyre.HARD || t == Tyre.MEDIUM)
            Text(
                t.label,
                modifier = Modifier.padding(start = 6.dp),
                color = tyreColor(t),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                style = if (halo) TextStyle(shadow = Shadow(Color(0xAA000000), Offset.Zero, blurRadius = 4f)) else TextStyle.Default,
            )
        }
    }
}

private fun tyreColor(t: Tyre): Color = when (t) {
    Tyre.HARD -> Color.White
    Tyre.SOFT -> Color(0xFFFF3B30)
    Tyre.MEDIUM -> Color(0xFFFFD60A)
    Tyre.INTER -> Color(0xFF34C759)
    Tyre.WET -> Color(0xFF3A8DFF)
}

/** Result rows shared by Last race and the season race screen. */
fun LazyListScope.resultItems(race: F1Race, fav: Favorites) {
    item { SimpleRow("Pos", "Driver", "", "Grid", "Pts", header = true) }
    items(race.results) { r ->
        SimpleRow(
            pos = r.pos,
            main = r.driver + if (r.fastestLap) "  (fastest lap)" else "",
            sub = "${r.team}  •  ${r.timeOrStatus}",
            a = r.grid,
            b = r.points,
            highlight = fav.isFavDriver(r.driver),
            leading = { TeamBadge(r.team) },
        )
    }
}

/** Every round this season: results for completed races (tap for the full classification), dates for upcoming ones. */
@Composable
private fun SeasonView(open: (Route) -> Unit) {
    val polled = rememberPolled<List<F1Round>>("f1season", { IDLE_MS * 2 }) { F1.season() }
    var upcoming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !upcoming, onClick = { upcoming = false }, label = { Text("Results") })
            FilterChip(selected = upcoming, onClick = { upcoming = true }, label = { Text("Upcoming") })
        }
        LoadableContent(polled) { rounds ->
            val now = java.time.Instant.now()
            fun started(r: F1Round) = r.winner != null ||
                (F1ExtrasParse.instant(r.dateIso.let { if ('T' in it) it else it + "T23:59:00Z" })?.isBefore(now) == true)
            val list = if (upcoming) rounds.filterNot(::started) else rounds.filter(::started).reversed()
            if (list.isEmpty()) {
                Message(if (upcoming) "No races left this season." else "No completed races yet this season.")
                return@LoadableContent
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.round }) { r ->
                    RoundCard(r, next = upcoming && r == list.first()) {
                        if (!upcoming) open(Route.F1Race(r.round, r.name, r.hasSprint))
                    }
                }
                item { SourceNote() }
            }
        }
    }
}

@Composable
private fun RoundCard(r: F1Round, next: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = if (next) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("R${r.round}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (r.hasSprint) Text("Sprint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (next) {
                        Spacer(Modifier.width(8.dp))
                        Text("NEXT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    listOf(r.circuit, r.location).filter { it.isNotBlank() }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    roundDate(r.dateIso),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.winner != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamBadge(r.winnerTeam ?: "", 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("🏆 ${r.winner}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                } else if ('T' in r.dateIso && F1ExtrasParse.instant(r.dateIso)?.isBefore(java.time.Instant.now()) == true) {
                    Text("Results pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** "Sun, Sep 26 • 7:00 AM" in local time, or just the date when Jolpica has no start time. */
private fun roundDate(iso: String): String = runCatching {
    if ('T' in iso) {
        java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d  •  h:mm a"))
    } else {
        java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}.getOrDefault(iso)

/** Full classification for one round, with a Sprint tab on sprint weekends. */
@Composable
fun F1RaceScreen(round: String, name: String, hasSprint: Boolean, onBack: () -> Unit) {
    val fav = Favorites.get(LocalContext.current)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Missing results load as an empty race, so the screen shows a message instead of spinning.
    val empty = F1Race(name, round, "", "", emptyList())
    val race = rememberPolled<F1Race>("f1race-$round", { IDLE_MS * 6 }) { F1.raceResults(round) ?: empty }
    val sprint = if (hasSprint) rememberPolled<F1Race>("f1sprint-$round", { IDLE_MS * 6 }) { F1.sprintResults(round) ?: empty } else null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = scorelineTopBarColors(),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (sprint != null) {
                TabRow(selectedTabIndex = tab) {
                    listOf("Race", "Sprint").forEachIndexed { i, s -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s) }) }
                }
            }
            val shown = if (tab == 1 && sprint != null) sprint else race
            LoadableContent(shown) { r ->
                if (r.results.isEmpty()) {
                    Message("Results aren't posted yet.")
                    return@LoadableContent
                }
                LazyColumn {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("Round ${r.round}  •  ${r.circuit}  •  ${r.date}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    resultItems(r, fav)
                    item { SourceNote() }
                }
            }
        }
    }
}
